package kr.toxicity.model.api.tracker;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bone.BoneName;
import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.config.DebugConfig;
import kr.toxicity.model.api.data.blueprint.BlueprintAnimation;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.data.renderer.RenderPipeline;
import kr.toxicity.model.api.event.*;
import kr.toxicity.model.api.nms.ModelDisplay;
import kr.toxicity.model.api.nms.PacketBundler;
import kr.toxicity.model.api.nms.PlayerChannelHandler;
import kr.toxicity.model.api.util.*;
import kr.toxicity.model.api.util.function.BonePredicate;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Tracker of a model.
 */
public abstract class Tracker implements AutoCloseable {

    private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(256, new ThreadFactory() {

        private final AtomicInteger integer = new AtomicInteger();

        @Override
        public Thread newThread(@NotNull Runnable r) {
            var thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("BetterModel-Worker-" + integer.getAndIncrement());
            thread.setUncaughtExceptionHandler((t, e) -> LogUtil.handleException("Exception has occurred in " + t.getName(), e));
            return thread;
        }
    });
    /**
     * Tracker tick interval
     */
    public static final int TRACKER_TICK_INTERVAL = 10;
    /**
     * Multiplier value for convert tracker tick to minecraft tick
     */
    public static final int MINECRAFT_TICK_MULTIPLIER = MathUtil.MINECRAFT_TICK_MILLS / TRACKER_TICK_INTERVAL;

    @Getter
    protected final RenderPipeline pipeline;
    private volatile ScheduledFuture<?> task;
    private long frame = 0;
    private final Queue<Runnable> queuedTask = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isClosed = new AtomicBoolean();
    private final AtomicBoolean readyForForceUpdate = new AtomicBoolean();
    private final AtomicBoolean forRemoval = new AtomicBoolean();
    private final AtomicBoolean rotationLock = new AtomicBoolean();
    private final TrackerModifier modifier;
    private final Runnable updater;
    private final BundlerSet bundlerSet;
    private ModelRotator rotator = ModelRotator.YAW;
    private ModelScaler scaler = ModelScaler.entity();
    private Supplier<ModelRotation> rotationSupplier = () -> ModelRotation.EMPTY;
    private Consumer<Tracker> closeEventHandler = t -> EventUtil.call(new CloseTrackerEvent(t));

    private ScheduledPacketHandler handler = (t, s) -> t.pipeline.tick(s.viewBundler);

    /**
     * Creates tracker
     * @param pipeline target instance
     * @param modifier modifier
     */
    public Tracker(@NotNull RenderPipeline pipeline, @NotNull TrackerModifier modifier) {
        this.pipeline = pipeline;
        this.modifier = modifier;
        bundlerSet = new BundlerSet();
        var config = BetterModel.config();
        updater = () -> {
            var isMinecraftTickTime = frame % MINECRAFT_TICK_MULTIPLIER == 0;
            if (isMinecraftTickTime) {
                Runnable task;
                while ((task = queuedTask.poll()) != null) task.run();
            }
            handler.handle(this, bundlerSet);
            bundlerSet.send();
        };
        if (modifier.sightTrace()) pipeline.viewFilter(p -> EntityUtil.canSee(p.getEyeLocation(), location()));
        frame((t, s) -> {
            if (readyForForceUpdate.compareAndSet(true, false)) t.pipeline.forceUpdate(s.dataBundler);
        });
        tick((t, s) -> pipeline.rotate(
                (t.isRunningSingleAnimation() && config.lockOnPlayAnimation()) ? t.pipeline.getRotation() : t.rotation(),
                s.tickBundler
        ));
        tick((t, s) -> t.pipeline.getScriptProcessor().tick());
        pipeline.spawnPacketHandler(p -> start());
    }

    /**
     * Returns this model is being scheduled.
     * @return is scheduled
     */
    public boolean isScheduled() {
        return task != null && !task.isCancelled();
    }

    private void start() {
        if (isScheduled()) return;
        synchronized (this) {
            if (isScheduled()) return;
            task = EXECUTOR.scheduleAtFixedRate(() -> {
                if (playerCount() == 0 && !forRemoval.get()) {
                    shutdown();
                    return;
                }
                updater.run();
                frame++;
            }, TRACKER_TICK_INTERVAL, TRACKER_TICK_INTERVAL, TimeUnit.MILLISECONDS);
            LogUtil.debug(DebugConfig.DebugOption.TRACKER, () -> getClass().getSimpleName() + " scheduler started: " + name());
        }
    }

    private void shutdown() {
        if (!isScheduled()) return;
        synchronized (this) {
            if (!isScheduled()) return;
            task.cancel(true);
            task = null;
            frame = 0;
            LogUtil.debug(DebugConfig.DebugOption.TRACKER, () -> getClass().getSimpleName() + " scheduler shutdown: " + name());
        }
    }

    /**
     * Gets model rotation.
     * @return rotation
     */
    public @NotNull ModelRotation rotation() {
        return rotationLock.get() ? pipeline.getRotation() : rotator.apply(this, rotationSupplier.get());
    }

    /**
     * Locks model rotation.
     * @return success
     */
    public boolean lockRotation(boolean lock) {
        return rotationLock.compareAndSet(!lock, lock);
    }

    public final void rotation(@NotNull Supplier<ModelRotation> supplier) {
        this.rotationSupplier = Objects.requireNonNull(supplier);
    }

    public final void rotator(@NotNull ModelRotator rotator) {
        this.rotator = Objects.requireNonNull(rotator);
    }

    public @NotNull ModelScaler scaler() {
        return scaler;
    }

    public void scaler(@NotNull ModelScaler scaler) {
        this.scaler = Objects.requireNonNull(scaler);
    }

    public @NotNull TrackerData asTrackerData() {
        return new TrackerData(
                name(),
                scaler,
                rotator,
                modifier
        );
    }

    /**
     * Register this task on next tick
     * @param runnable task
     */
    public void task(@NotNull Runnable runnable) {
        queuedTask.add(Objects.requireNonNull(runnable));
    }

    /**
     * Runs consumer on frame.
     * @param handler handler
     */
    public void frame(@NotNull ScheduledPacketHandler handler) {
        this.handler = this.handler.then(Objects.requireNonNull(handler));
    }
    /**
     * Runs consumer on tick.
     * @param handler handler
     */
    public void tick(@NotNull ScheduledPacketHandler handler) {
        tick(1, handler);
    }
    /**
     * Runs consumer on tick.
     * @param tick tick
     * @param handler handler
     */
    public void tick(long tick, @NotNull ScheduledPacketHandler handler) {
        schedule(MINECRAFT_TICK_MULTIPLIER * tick, handler);
    }

    /**
     * Schedules some task.
     * @param period period
     * @param handler handler
     */
    public void schedule(long period, @NotNull ScheduledPacketHandler handler) {
        Objects.requireNonNull(handler);
        if (period <= 0) throw new RuntimeException("period cannot be <= 0");
        frame((t, s) -> {
            if (frame % period == 0) handler.handle(t, s);
        });
    }

    protected void update() {
        updater.run();
    }

    /**
     * Gets tracker name
     * @return name
     */
    public @NotNull String name() {
        return pipeline.name();
    }

    /**
     * Gets tracker model's height
     * @return height
     */
    public double height() {
        return pipeline.height();
    }

    /**
     * Checks this tracker is closed
     * @return is closed
     */
    public boolean isClosed() {
        return isClosed.get();
    }

    @Override
    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            closeEventHandler.accept(this);
            shutdown();
            pipeline.despawn();
            LogUtil.debug(DebugConfig.DebugOption.TRACKER, () -> getClass().getSimpleName() + " closed: " + name());
        }
    }

    /**
     * Despawns this tracker to all players
     */
    public void despawn() {
        if (!isClosed()) {
            pipeline.despawn();
            LogUtil.debug(DebugConfig.DebugOption.TRACKER, () -> getClass().getSimpleName() + " despawned: " + name());
        }
    }

    /**
     * Gets tracker modifier
     * @return tracker modifier
     */
    public @NotNull TrackerModifier modifier() {
        return modifier;
    }


    /**
     * Forces packet update.
     * @param force force
     * @return success
     */
    public boolean forceUpdate(boolean force) {
        var get = readyForForceUpdate.get();
        return readyForForceUpdate.compareAndSet(get, force);
    }

    /**
     * Gets whether this model is playing single animation.
     * @return whether to playing single.
     */
    public boolean isRunningSingleAnimation() {
        var runningAnimation = pipeline.runningAnimation();
        return runningAnimation != null && runningAnimation.type() == AnimationIterator.Type.PLAY_ONCE;
    }

    /**
     * Creates model spawn packet and registers player.
     * @param player target player
     * @param bundler bundler
     * @return success
     */
    protected boolean spawn(@NotNull Player player, @NotNull PacketBundler bundler) {
        if (isClosed()) return false;
        if (!EventUtil.call(new ModelSpawnAtPlayerEvent(player, this))) return false;
        var result = pipeline.spawn(player, bundler);
        if (result) LogUtil.debug(DebugConfig.DebugOption.TRACKER, () -> getClass().getSimpleName() + " is spawned at player " + player.getName() + ": " + name());
        return result;
    }

    /**
     * Removes model from player
     * @param player player
     * @return success
     */
    public boolean remove(@NotNull Player player) {
        if (isClosed()) return false;
        EventUtil.call(new ModelDespawnAtPlayerEvent(player, this));
        var result = pipeline.remove(player);
        if (result) LogUtil.debug(DebugConfig.DebugOption.TRACKER, () -> getClass().getSimpleName() + " is despawned at player " + player.getName() + ": " + name());
        return result;
    }

    /**
     * Gets number of viewed players.
     * @return viewed player amount
     */
    public int playerCount() {
        return pipeline.playerCount();
    }

    /**
     * Gets viewed players.
     * @return viewed players list
     */
    public @NotNull Stream<Player> viewedPlayer() {
        return pipeline.viewedPlayer();
    }

    /**
     * Toggles red tint of a model.
     * @param rgb toggle
     */
    public void tint(int rgb) {
        tint(BonePredicate.TRUE, rgb);
    }

    /**
     * Toggles red tint of a model.
     * @param predicate predicate
     * @param rgb toggle
     */
    public void tint(@NotNull BonePredicate predicate, int rgb) {
        if (pipeline.tint(predicate, rgb)) forceUpdate(true);
    }

    /**
     * Gets location of a model.
     * @return location
     */
    public abstract @NotNull Location location();

    /**
     * Gets uuid of a model.
     * @return uuid
     */
    public abstract @NotNull UUID uuid();


    /**
     * Players this animation by once
     * @param animation animation's name
     * @return success
     */
    public boolean animate(@NotNull String animation) {
        return animate(animation, AnimationModifier.DEFAULT);
    }

    /**
     * Players this animation by once
     * @param animation animation's name
     * @param modifier modifier
     * @return success
     */
    public boolean animate(@NotNull String animation, AnimationModifier modifier) {
        return animate(animation, modifier, () -> {});
    }

    /**
     * Players this animation by once
     * @param animation animation's name
     * @param modifier modifier
     * @param removeTask remove task
     * @return success
     */
    public boolean animate(@NotNull String animation, AnimationModifier modifier, Runnable removeTask) {
        return animate(e -> true, animation, modifier, removeTask);
    }

    /**
     * Players this animation by once
     * @param filter bone predicate
     * @param animation animation's name
     * @param modifier modifier
     * @param removeTask remove task
     * @return success
     */
    public boolean animate(@NotNull Predicate<RenderedBone> filter, @NotNull String animation, @NotNull AnimationModifier modifier, @NotNull Runnable removeTask) {
        return pipeline.animate(filter, animation, modifier, removeTask);
    }

    /**
     * Players this animation by once
     * @param filter bone predicate
     * @param animation animation
     * @param modifier modifier
     * @param removeTask remove task
     */
    public void animate(@NotNull Predicate<RenderedBone> filter, @NotNull BlueprintAnimation animation, @NotNull AnimationModifier modifier, @NotNull Runnable removeTask) {
        pipeline.animate(filter, animation, modifier, removeTask);
    }

    /**
     * Stops some animation
     * @param animation animation's name
     */
    public void stopAnimation(@NotNull String animation) {
        stopAnimation(e -> true, animation);
    }

    /**
     * Stops some animation
     * @param filter bone predicate
     * @param animation animation's name
     */
    public void stopAnimation(@NotNull Predicate<RenderedBone> filter, @NotNull String animation) {
        pipeline.stopAnimation(filter, animation);
    }

    /**
     * Replaces some animation by loop
     * @param target old animation's name
     * @param animation new animation's name
     * @param modifier modifier
     * @return success
     */
    public boolean replace(@NotNull String target, @NotNull String animation, @NotNull AnimationModifier modifier) {
        return replace(t -> true, target, animation, modifier);
    }

    /**
     * Replaces some animation by loop
     * @param filter bone predicate
     * @param target old animation's name
     * @param animation new animation's name
     * @param modifier modifier
     * @return success
     */
    public boolean replace(@NotNull Predicate<RenderedBone> filter, @NotNull String target, @NotNull String animation, @NotNull AnimationModifier modifier) {
        return pipeline.replace(filter, target, animation, modifier);
    }

    /**
     * Replaces some animation by loop
     * @param filter bone predicate
     * @param target old animation's name
     * @param animation new animation
     * @param modifier modifier
     */
    public void replace(@NotNull Predicate<RenderedBone> filter, @NotNull String target, @NotNull BlueprintAnimation animation, @NotNull AnimationModifier modifier) {
        pipeline.replace(filter, target, animation, modifier);
    }

    /**
     * Toggles some part
     * @param predicate predicate
     * @param toggle toggle
     * @return success
     */
    public boolean togglePart(@NotNull BonePredicate predicate, boolean toggle) {
        return pipeline.togglePart(predicate, toggle);
    }

    /**
     * Sets item of some model part
     * @param predicate predicate
     * @param itemStack item
     * @return success
     */
    public boolean itemStack(@NotNull BonePredicate predicate, @NotNull TransformedItemStack itemStack) {
        return pipeline.itemStack(predicate, itemStack);
    }

    /**
     * Sets glow of some model part
     * @param glow glow
     * @param glowColor glowColor
     * @return success
     */
    public boolean glow(@NotNull BonePredicate predicate, boolean glow, int glowColor) {
        return pipeline.glow(predicate, glow, glowColor);
    }

    /**
     * Sets enchantment of some model part
     * @param predicate predicate
     * @param enchant should enchant
     * @return success
     */
    public boolean enchant(@NotNull BonePredicate predicate, boolean enchant) {
        return pipeline.enchant(predicate, enchant);
    }

    /**
     * Sets brightness of some model part
     * @param predicate predicate
     * @param block block light
     * @param sky skylight
     * @return success
     */
    public boolean brightness(@NotNull BonePredicate predicate, int block, int sky) {
        return pipeline.brightness(predicate, block, sky);
    }
    /**
     * Updates item
     * @param predicate predicate
     * @return success
     */
    public boolean updateItem(@NotNull BonePredicate predicate) {
        return pipeline.updateItem(predicate);
    }

    /**
     * Gets bone by bone's name
     * @param name bone's name
     * @return bone or null
     */
    public @Nullable RenderedBone bone(@NotNull BoneName name) {
        return bone(b -> b.getName().equals(name));
    }

    /**
     * Gets bone by bone's name
     * @param name bone's name
     * @return bone or null
     */
    public @Nullable RenderedBone bone(@NotNull String name) {
        return bone(b -> b.getName().name().equals(name));
    }

    /**
     * Gets bone by bone's name
     * @param predicate bone's predicate
     * @return bone or null
     */
    public @Nullable RenderedBone bone(@NotNull Predicate<RenderedBone> predicate) {
        return pipeline.boneOf(predicate);
    }

    /**
     * Gets all bones
     * @return all bones
     */
    public @NotNull @Unmodifiable List<RenderedBone> bones() {
        return pipeline.bones();
    }

    /**
     * Gets all model displays
     * @return all model displays
     */
    public @NotNull Stream<ModelDisplay> displays() {
        return bones().stream()
                .map(RenderedBone::getDisplay)
                .filter(Objects::nonNull);
    }

    /**
     * Hides this tracker to some player
     * @param player target player
     * @return success
     */
    public boolean hide(@NotNull Player player) {
        return EventUtil.call(new PlayerHideTrackerEvent(this, player)) && pipeline.hide(player);
    }

    /**
     * Checks this player is marked as hide
     * @param player target player
     * @return hide
     */
    public boolean isHide(@NotNull Player player) {
        return pipeline.isHide(player);
    }

    /**
     * Shows this tracker to some player
     * @param player target player
     * @return success
     */
    public boolean show(@NotNull Player player) {
        return EventUtil.call(new PlayerShowTrackerEvent(this, player)) && pipeline.show(player);
    }

    public void handleCloseEvent(@NotNull Consumer<Tracker> consumer) {
        closeEventHandler = closeEventHandler.andThen(Objects.requireNonNull(consumer));
    }

    /**
     * Gets the renderer of this tracker
     * @return renderer
     */
    public @NotNull ModelRenderer renderer() {
        return pipeline.getParent();
    }

    /**
     * Marks future will remove this tracker
     * @param removal removal
     */
    @ApiStatus.Internal
    public void forRemoval(boolean removal) {
        forRemoval.set(removal);
    }

    /**
     * Checks this tracker is for removal
     * @return for removal
     */
    @ApiStatus.Internal
    public boolean forRemoval() {
        return forRemoval.get();
    }


    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Tracker tracker)) return false;
        return name().equals(tracker.name());
    }

    @Override
    public int hashCode() {
        return name().hashCode();
    }

    /**
     * Scheduled packet handler
     */
    @FunctionalInterface
    public interface ScheduledPacketHandler {
        /**
         * Handles packet
         * @param tracker tracker
         * @param bundlerSet bundler set
         */
        void handle(@NotNull Tracker tracker, @NotNull BundlerSet bundlerSet);

        /**
         * Plus another handler
         * @param other other
         * @return merged handler
         */
        default @NotNull ScheduledPacketHandler then(@NotNull ScheduledPacketHandler other) {
            return (t, s) -> {
                handle(t, s);
                other.handle(t, s);
            };
        }
    }

    /**
     * Bundler set
     */
    @Getter
    public class BundlerSet {
        private PacketBundler tickBundler = pipeline.createBundler();
        private PacketBundler viewBundler = pipeline.createBundler();
        private PacketBundler dataBundler = pipeline.createBundler();

        /**
         * Private initializer
         */
        private BundlerSet() {
        }

        private void send() {
            if (!tickBundler.isEmpty()) {
                pipeline.allPlayer().map(PlayerChannelHandler::player).forEach(tickBundler::send);
                tickBundler = pipeline.createBundler();
            }
            if (!dataBundler.isEmpty()) {
                pipeline.nonHidePlayer().forEach(dataBundler::send);
                dataBundler = pipeline.createBundler();
            }
            if (!viewBundler.isEmpty()) {
                pipeline.viewedPlayer().forEach(viewBundler::send);
                viewBundler = pipeline.createBundler();
            }
        }
    }
}

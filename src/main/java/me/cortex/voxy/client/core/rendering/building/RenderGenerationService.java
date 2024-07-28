package me.cortex.voxy.client.core.rendering.building;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.OffThreadModelBakerySystem;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Supplier;

//TODO: Add a render cache
public class RenderGenerationService {
    public interface TaskChecker {boolean check(int lvl, int x, int y, int z);}
    private record BuildTask(long position, Supplier<WorldSection> sectionSupplier, boolean[] hasDoneModelRequest) {}

    private volatile boolean running = true;
    private final Thread[] workers;

    private final Long2ObjectLinkedOpenHashMap<BuildTask> taskQueue = new Long2ObjectLinkedOpenHashMap<>();

    private final Semaphore taskCounter = new Semaphore(0);
    private final WorldEngine world;
    private final OffThreadModelBakerySystem modelBakery;
    private final Consumer<BuiltSection> resultConsumer;
    private final BuiltSectionMeshCache meshCache = new BuiltSectionMeshCache();
    private final boolean emitMeshlets;

    public RenderGenerationService(WorldEngine world, OffThreadModelBakerySystem modelBakery, int workers, Consumer<BuiltSection> consumer, boolean emitMeshlets) {
        this.emitMeshlets = emitMeshlets;
        this.world = world;
        this.modelBakery = modelBakery;
        this.resultConsumer = consumer;
        this.workers =  new Thread[workers];
        for (int i = 0; i < workers; i++) {
            this.workers[i] = new Thread(this::renderWorker);
            this.workers[i].setDaemon(true);
            this.workers[i].setName("Render generation service #" + i);
            this.workers[i].start();
        }
    }

    //NOTE: the biomes are always fully populated/kept up to date

    //Asks the Model system to bake all blocks that currently dont have a model
    private void computeAndRequestRequiredModels(WorldSection section) {
        var raw = section.copyData();//TODO: replace with copyDataTo and use a "thread local"/context array to reduce allocation rates
        IntOpenHashSet seen = new IntOpenHashSet(128);
        for (long state : raw) {
            int block = Mapper.getBlockId(state);
            if (!this.modelBakery.factory.hasModelForBlockId(block)) {
                if (seen.add(block)) {
                    this.modelBakery.requestBlockBake(block);
                }
            }
        }
    }

    //TODO: add a generated render data cache
    private void renderWorker() {
        //Thread local instance of the factory
        var factory = new RenderDataFactory(this.world, this.modelBakery.factory, this.emitMeshlets);
        while (this.running) {
            this.taskCounter.acquireUninterruptibly();
            if (!this.running) break;
            try {
                BuildTask task;
                synchronized (this.taskQueue) {
                    task = this.taskQueue.removeFirst();
                }
                var section = task.sectionSupplier.get();
                if (section == null) {
                    this.resultConsumer.accept(new BuiltSection(task.position));
                    continue;
                }
                section.assertNotFree();
                BuiltSection mesh = null;
                try {
                    mesh = factory.generateMesh(section);
                } catch (IdNotYetComputedException e) {
                    if (task.hasDoneModelRequest[0]) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ex) {
                            throw new RuntimeException(ex);
                        }
                    } else {
                        this.computeAndRequestRequiredModels(section);
                    }
                    //We need to reinsert the build task into the queue
                    //System.err.println("Render task failed to complete due to un-computed client id");
                    synchronized (this.taskQueue) {
                        var queuedTask = this.taskQueue.computeIfAbsent(section.key, (a)->task);
                        queuedTask.hasDoneModelRequest[0] = true;//Mark (or remark) the section as having chunks requested

                        if (queuedTask == task) {//use the == not .equal to see if we need to release a permit
                            this.taskCounter.release();//Since we put in queue, release permit
                        }
                    }
                }

                //TODO: if the section was _not_ built, maybe dont release it, or release it with the hint
                section.release();
                if (mesh != null) {
                    //TODO: if the mesh is null, need to clear the cache at that point
                    this.resultConsumer.accept(mesh.clone());
                    if (!this.meshCache.putMesh(mesh)) {
                        mesh.free();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                MinecraftClient.getInstance().executeSync(()->MinecraftClient.getInstance().player.sendMessage(Text.literal("Voxy render service had an exception while executing please check logs and report error")));
            }
        }
    }

    public int getMeshCacheCount() {
        return this.meshCache.getCount();
    }

    //TODO: Add a priority system, higher detail sections must always be updated before lower detail
    // e.g. priorities NONE->lvl0 and lvl1 -> lvl0 over lvl0 -> lvl1


    //TODO: make it pass either a world section, _or_ coodinates so that the render thread has to do the loading of the sections
    // not the calling method

    //TODO: maybe make it so that it pulls from the world to stop the inital loading absolutly butt spamming the queue
    // and thus running out of memory

    //TODO: REDO THIS ENTIRE THING
    // render tasks should not be bound to a WorldSection, instead it should be bound to either a WorldSection or
    // an LoD position, the issue is that if we bound to a LoD position we loose all the info of the WorldSection
    // like if its in the render queue and if we should abort building the render data
    //1 proposal fix is a Long2ObjectLinkedOpenHashMap<WorldSection> which means we can abort if needed,
    // also gets rid of dependency on a WorldSection (kinda)
    public void enqueueTask(int lvl, int x, int y, int z) {
        this.enqueueTask(lvl, x, y, z, (l,x1,y1,z1)->true);
    }


    public void enqueueTask(int lvl, int x, int y, int z, TaskChecker checker) {
        long ikey = WorldEngine.getWorldSectionId(lvl, x, y, z);
        {
            var cache = this.meshCache.getMesh(ikey);
            if (cache != null) {
                this.resultConsumer.accept(cache);
                return;
            }
        }
        synchronized (this.taskQueue) {
            this.taskQueue.computeIfAbsent(ikey, key->{
                this.taskCounter.release();
                return new BuildTask(ikey, ()->{
                    if (checker.check(lvl, x, y, z)) {
                        return this.world.acquireIfExists(lvl, x, y, z);
                    } else {
                        return null;
                    }
                }, new boolean[1]);
            });
        }
    }

    //Tells the render cache that the mesh at the specified position should be cached
    public void markCache(int lvl, int x, int y, int z) {
        this.meshCache.markCache(WorldEngine.getWorldSectionId(lvl, x, y, z));
    }

    //Tells the render cache that the mesh at the specified position should not be cached/any previous cache result, freed
    public void unmarkCache(int lvl, int x, int y, int z) {
        this.meshCache.unmarkCache(WorldEngine.getWorldSectionId(lvl, x, y, z));
    }

    //Resets a chunks cache mesh
    public void clearCache(int lvl, int x, int y, int z) {
        this.meshCache.clearMesh(WorldEngine.getWorldSectionId(lvl, x, y, z));
    }

    public void removeTask(int lvl, int x, int y, int z) {
        synchronized (this.taskQueue) {
            if (this.taskQueue.remove(WorldEngine.getWorldSectionId(lvl, x, y, z)) != null) {
                this.taskCounter.acquireUninterruptibly();
            }
        }
    }

    public int getTaskCount() {
        return this.taskCounter.availablePermits();
    }

    public void shutdown() {
        boolean anyAlive = false;
        for (var worker : this.workers) {
            anyAlive |= worker.isAlive();
        }

        if (!anyAlive) {
            System.err.println("Render gen workers already dead on shutdown! this is very very bad, check log for errors from this thread");
            return;
        }

        //Since this is just render data, dont care about any tasks needing to finish
        this.running = false;
        this.taskCounter.release(1000);

        //Wait for thread to join
        try {
            for (var worker : this.workers) {
                worker.join();
            }
        } catch (InterruptedException e) {throw new RuntimeException(e);}

        //Cleanup any remaining data
        while (!this.taskQueue.isEmpty()) {
            this.taskQueue.removeFirst();
        }
        this.meshCache.free();
    }

    public void addDebugData(List<String> debug) {

    }
}

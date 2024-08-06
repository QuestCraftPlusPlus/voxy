package me.cortex.voxy.common.world.thread;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;


//TODO: could also probably replace all of this with just VirtualThreads and a Executors.newThreadPerTaskExecutor with a fixed thread pool
// it is probably better anyway
public class ServiceThreadPool {
    private volatile boolean running = true;
    private final Thread[] workers;
    private final Semaphore jobCounter = new Semaphore(0);

    private volatile ServiceSlice[] serviceSlices = new ServiceSlice[0];
    private final AtomicLong totalJobWeight = new AtomicLong();

    public ServiceThreadPool(int workers) {
        this.workers = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            int threadId = i;
            var worker = new Thread(()->this.worker(threadId));
            worker.setDaemon(false);
            worker.setName("Service worker #" + i);
            worker.start();
            worker.setUncaughtExceptionHandler(this::handleUncaughtException);
            this.workers[i]  = worker;
        }
    }

    public synchronized ServiceSlice createService(String name, int weight, Supplier<Runnable> workGenerator) {
        return this.createService(name, weight, workGenerator, ()->true);
    }

    public synchronized ServiceSlice createService(String name, int weight, Supplier<Runnable> workGenerator, BooleanSupplier executionCondition) {
        var current = this.serviceSlices;
        var newList = new ServiceSlice[current.length + 1];
        System.arraycopy(current, 0, newList, 0, current.length);
        var service = new ServiceSlice(this, workGenerator, name, weight, executionCondition);
        newList[current.length] = service;
        this.serviceSlices = newList;
        return service;
    }

    synchronized void removeService(ServiceSlice service) {
        this.removeServiceFromArray(service);
        this.totalJobWeight.addAndGet(-((long) service.weightPerJob) * service.jobCount.availablePermits());
        //Need to acquire all the shut-down jobs
        if (!this.jobCounter.tryAcquire(service.jobCount.availablePermits())) {
            throw new IllegalStateException("Failed to acquire all the permits for the shut down jobs");
        }
    }

    private synchronized void removeServiceFromArray(ServiceSlice service) {
        var lst = this.serviceSlices;
        int idx;
        for (idx = 0; idx < lst.length; idx++) {
            if (lst[idx] == service) {
                break;
            }
        }
        if (idx == lst.length) {
            throw new IllegalStateException("Service not in service list");
        }

        //Remove the slice from the array and set it back

        if (lst.length-1 == 0) {
            this.serviceSlices = new ServiceSlice[0];
            return;
        }

        ServiceSlice[] newArr = new ServiceSlice[lst.length-1];
        System.arraycopy(lst, 0, newArr, 0, idx);
        if (lst.length-1 != idx) {
            //Need to do a second copy
            System.arraycopy(lst, idx+1, newArr, idx, newArr.length-idx);
        }
        this.serviceSlices = newArr;
    }

    void execute(ServiceSlice service) {
        this.totalJobWeight.addAndGet(service.weightPerJob);
        this.jobCounter.release(1);
    }

    private void worker(int threadId) {
        long seed = 1234342;
        int revolvingSelector = 0;
        while (true) {
            this.jobCounter.acquireUninterruptibly();
            if (!this.running) {
                break;
            }

            int attempts = 50;
            outer:
            while (true) {
                if (attempts-- == 0) {
                    for (var service : this.serviceSlices) {
                        //Run the job
                        if (!service.doRun(threadId)) {
                            //Didnt consume the job, find a new job
                            continue;
                        }
                        //Consumed a job from the service, decrease weight by the amount
                        if (this.totalJobWeight.addAndGet(-service.weightPerJob)<0) {
                            throw new IllegalStateException("Total job weight is negative");
                        }
                        break outer;
                    }
                    throw new IllegalStateException("All attempts at executing a job failed! something critically wrong has occurred");
                }
                seed = (seed ^ seed >>> 30) * -4658895280553007687L;
                seed = (seed ^ seed >>> 27) * -7723592293110705685L;
                long clamped = seed&((1L<<63)-1);
                var ref = this.serviceSlices;
                if (ref.length == 0) {
                    System.err.println("Service worker tried to run but had 0 slices");
                    break;
                }


                ServiceSlice service = ref[(int) (clamped % ref.length)];
                //1 in 64 chance just to pick a service that has a task, in a cycling manor, this is to keep at least one service from overloading all services constantly
                if (((seed>>10)&63) == 0) {
                    for (int i = 0; i < ref.length; i++) {
                        int idx = (i+revolvingSelector)%ref.length;
                        var slice = ref[idx];
                        if (slice.hasJobs()) {
                            service = slice;
                            revolvingSelector = (idx+1)%ref.length;
                            break;
                        }
                    }

                } else {
                    long chosenNumber = clamped % this.totalJobWeight.get();
                    for (var slice : ref) {
                        chosenNumber -= ((long) slice.weightPerJob) * slice.jobCount.availablePermits();
                        if (chosenNumber <= 0) {
                            service = slice;
                            break;
                        }
                    }
                }

                //Run the job
                if (!service.doRun(threadId)) {
                    //Didnt consume the job, find a new job
                    continue;
                }
                //Consumed a job from the service, decrease weight by the amount
                if (this.totalJobWeight.addAndGet(-service.weightPerJob)<0) {
                    throw new IllegalStateException("Total job weight is negative");
                }
                break;
            }
        }
    }

    private void handleUncaughtException(Thread thread, Throwable throwable) {
        System.err.println("Service worker thread has exploded unexpectedly! this is really not good very very bad.");
        throwable.printStackTrace();
    }

    public void shutdown() {
        if (this.serviceSlices.length != 0) {
            throw new IllegalStateException("All service slices must be shutdown before thread pool can exit");
        }

        //Wait for the tasks to finish
        while (this.jobCounter.availablePermits() != 0) {
            Thread.onSpinWait();
        }

        //Shutdown
        this.running = false;
        this.jobCounter.release(1000);

        //Wait for thread to join
        try {
            for (var worker : this.workers) {
                worker.join();
            }
        } catch (InterruptedException e) {throw new RuntimeException(e);}

        if (this.totalJobWeight.get() != 0) {
            throw new IllegalStateException("Service pool job weight not 0 after shutdown");
        }
    }

    public int getThreadCount() {
        return this.workers.length;
    }
}

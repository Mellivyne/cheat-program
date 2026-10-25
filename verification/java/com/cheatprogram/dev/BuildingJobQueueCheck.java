package com.cheatprogram.dev;
import com.cheatprogram.util.BuildingJobQueue;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** No game: simulate servers retaining, dropping, or rejecting scheduled work. */
public final class BuildingJobQueueCheck {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        var discardedServer = new ArrayList<Runnable>();
        var newServer = new ArrayList<Runnable>();
        var oldJob = new BuildingJobQueue();
        var newJob = new BuildingJobQueue();
        var runs = new AtomicInteger();
        require(oldJob.submit(discardedServer::add, runs::incrementAndGet), "initial submission");
        require(!oldJob.submit(discardedServer::add, runs::incrementAndGet), "ticks must coalesce");
        require(newJob.submit(newServer::add, runs::incrementAndGet), "abandoned old server blocks new job");
        discardedServer.getFirst().run();
        require(!newJob.submit(newServer::add, runs::incrementAndGet), "old completion released new job");
        newServer.removeFirst().run();
        require(newJob.submit(Runnable::run, runs::incrementAndGet), "finished job cannot advance");
        require(runs.get() == 3, "unexpected number of steps");
        var rejected = new BuildingJobQueue();
        try { rejected.submit(r -> { throw new java.util.concurrent.RejectedExecutionException(); }, () -> {}); throw new AssertionError("rejection swallowed"); }
        catch (java.util.concurrent.RejectedExecutionException expected) { }
        require(rejected.submit(Runnable::run, runs::incrementAndGet), "rejected executor retained queue");
        try { rejected.submit(Runnable::run, () -> { throw new IllegalStateException(); }); throw new AssertionError("step failure swallowed"); }
        catch (IllegalStateException expected) { }
        require(rejected.submit(Runnable::run, runs::incrementAndGet), "failed step retained queue");
        var immediate = new BuildingJobQueue();
        require(immediate.submit(Runnable::run, () -> require(!immediate.submit(Runnable::run, () -> {}), "reentrant duplicate")), "synchronous submission");
        require(immediate.submit(Runnable::run, () -> {}), "synchronous queue retained");
        System.out.println("BUILDING_JOB_QUEUE_PASS abandoned-server coalescing stale-completion executor-rejection step-failure reentrancy");
    }
}

/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import com.lmax.disruptor.util.Util;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WorkPool整体表示一个消费者，当handler处理较慢，但是可以并发执行时，可以采用WorkPool提升消费速度。
 * (每一个handler都会有一个单独的线程，这些handler共同完成事件处理，每一个事件只会被其中某一个handler处理)
 *
 * WorkerPool contains a pool of {@link WorkProcessor}s that will consume sequences so jobs can be farmed out across a pool of workers.
 * Each of the {@link WorkProcessor}s manage and calls a {@link WorkHandler} to process the events.
 *
 * @param <T> event to be processed by a pool of workers
 */
public final class WorkerPool<T>
{
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * 消费者的进度取决于最小的Sequence。每一个WorkProcessor都有一个Sequence，根据WorkProcessor的Sequence可以得到消费者的进度。
     *
     * 1.那么问题来了，WorkerPool还带一个Sequence干嘛呢？
     * 答案：是WorkProcessor竞争通信的媒介！预分配(抢占)序号用的，竞争成功表示告诉其他workProcessor去消费下一个序号。
     * workSequence总是大于workProcessors的sequence的，因此它并不代表消费者的进度。workSequence甚至可能大于生产者的生产进度。
     *
     * WorkProcessor首先与workSequence同步，然后CAS更新workSequence (+1)。
     * 更新成功之后，workProcessor的进度处在workSequence更新之前进度上，就算有多个WorkProcessor进行了预分配，
     * 总有一个WorkProcessor的Sequence处于正确的进度。由于消费者的进度由最小的Sequence决定，
     * 因此workSequence的预分配更新并不会影响WorkerPool代表的消费者的消费进度。
     *
     * 2.预分配序号时+1的意义？
     * 保证了WorkerPool代表的消费者的进度是1格1格前进的，且尽可能的使所有的线程都在处理事件(保证执行效率)。
     *
     * 3.WorkerPool中最少有两个Sequence，WorkProcessor 和 WorkerPool各带一个。
     */
    private final Sequence workSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    private final RingBuffer<T> ringBuffer;

    // 事件处理器，每一个都是一个无限循环的任务，因此都需要独立的线程
    // WorkProcessors are created to wrap each of the provided WorkHandlers
    private final WorkProcessor<?>[] workProcessors;

    /**
     * Create a worker pool to enable an array of {@link WorkHandler}s to consume published sequences.
     * <p>
     * This option requires a pre-configured {@link RingBuffer} which must have {@link RingBuffer#addGatingSequences(Sequence...)}
     * called before the work pool is started.
     *
     * @param ringBuffer       of events to be consumed.
     * @param sequenceBarrier  on which the workers will depend.
     * @param exceptionHandler to callback when an error occurs which is not handled by the {@link WorkHandler}s.
     * @param workHandlers     to distribute the work load across.
     */
    @SafeVarargs
    public WorkerPool(
        final RingBuffer<T> ringBuffer,
        final SequenceBarrier sequenceBarrier,
        final ExceptionHandler<? super T> exceptionHandler,
        final WorkHandler<? super T>... workHandlers)
    {
        this.ringBuffer = ringBuffer;
        final int numWorkers = workHandlers.length;
        workProcessors = new WorkProcessor[numWorkers];

        for (int i = 0; i < numWorkers; i++)
        {
            workProcessors[i] = new WorkProcessor<>(
                ringBuffer,
                sequenceBarrier,
                workHandlers[i],
                exceptionHandler,
                workSequence);
        }
    }

    /**
     * Construct a work pool with an internal {@link RingBuffer} for convenience.
     * <p>
     * This option does not require {@link RingBuffer#addGatingSequences(Sequence...)} to be called before the work pool is started.
     *
     * @param eventFactory     for filling the {@link RingBuffer}
     * @param exceptionHandler to callback when an error occurs which is not handled by the {@link WorkHandler}s.
     * @param workHandlers     to distribute the work load across.
     */
    @SafeVarargs
    public WorkerPool(
        final EventFactory<T> eventFactory,
        final ExceptionHandler<? super T> exceptionHandler,
        final WorkHandler<? super T>... workHandlers)
    {
        ringBuffer = RingBuffer.createMultiProducer(eventFactory, 1024, new BlockingWaitStrategy());
        final SequenceBarrier barrier = ringBuffer.newBarrier();
        final int numWorkers = workHandlers.length;
        workProcessors = new WorkProcessor[numWorkers];

        for (int i = 0; i < numWorkers; i++)
        {
            workProcessors[i] = new WorkProcessor<>(
                ringBuffer,
                barrier,
                workHandlers[i],
                exceptionHandler,
                workSequence);
        }

        ringBuffer.addGatingSequences(getWorkerSequences());
    }

    /**
     * Get an array of {@link Sequence}s representing the progress of the workers.
     *
     * @return an array of {@link Sequence}s representing the progress of the workers.
     */
    public Sequence[] getWorkerSequences()
    {
        final Sequence[] sequences = new Sequence[workProcessors.length + 1];
        for (int i = 0, size = workProcessors.length; i < size; i++)
        {
            sequences[i] = workProcessors[i].getSequence();
        }
        sequences[sequences.length - 1] = workSequence;

        return sequences;
    }

    /**
     * Start the worker pool processing events in sequence.
     *
     * @param executor providing threads for running the workers.
     * @return the {@link RingBuffer} used for the work queue.
     * @throws IllegalStateException if the pool has already been started and not halted yet
     */
    public RingBuffer<T> start(final Executor executor)
    {
        if (!started.compareAndSet(false, true))
        {
            throw new IllegalStateException("WorkerPool has already been started and cannot be restarted until halted.");
        }

        final long cursor = ringBuffer.getCursor();
        workSequence.set(cursor);

        for (WorkProcessor<?> processor : workProcessors)
        {
            processor.getSequence().set(cursor);
            executor.execute(processor);
        }

        return ringBuffer;
    }

    /**
     * Wait for the {@link RingBuffer} to drain of published events then halt the workers.
     */
    public void drainAndHalt()
    {
        Sequence[] workerSequences = getWorkerSequences();
        while (ringBuffer.getCursor() > Util.getMinimumSequence(workerSequences))
        {
            Thread.yield();
        }

        for (WorkProcessor<?> processor : workProcessors)
        {
            processor.halt();
        }

        started.set(false);
    }

    /**
     * Halt all workers immediately at the end of their current cycle.
     */
    public void halt()
    {
        for (WorkProcessor<?> processor : workProcessors)
        {
            processor.halt();
        }

        started.set(false);
    }

    public boolean isRunning()
    {
        return started.get();
    }
}

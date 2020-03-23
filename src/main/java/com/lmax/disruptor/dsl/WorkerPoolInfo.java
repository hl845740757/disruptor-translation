package com.lmax.disruptor.dsl;

import com.lmax.disruptor.*;

import java.util.concurrent.Executor;

/**
 * 线程池消费者信息对象/工作者池信息。
 * （WorkPool整体是一个消费者，是一个多线程的消费者）
 * @param <T>
 */
class WorkerPoolInfo<T> implements ConsumerInfo
{
	/**
	 * 工作者池
	 */
    private final WorkerPool<T> workerPool;
	/**
	 * 消费者对应的序列屏障，屏障用于协调与生产者或其他消费者之间的速度和保证可见性
	 */
    private final SequenceBarrier sequenceBarrier;
	/**
	 * 是否是消费链的末端消费者(没有后继消费者)
	 */
    private boolean endOfChain = true;

    WorkerPoolInfo(final WorkerPool<T> workerPool, final SequenceBarrier sequenceBarrier)
    {
        this.workerPool = workerPool;
        this.sequenceBarrier = sequenceBarrier;
    }

    @Override
    public Sequence[] getSequences()
    {
        return workerPool.getWorkerSequences();
    }

    @Override
    public SequenceBarrier getBarrier()
    {
        return sequenceBarrier;
    }

    @Override
    public boolean isEndOfChain()
    {
        return endOfChain;
    }

    @Override
    public void start(Executor executor)
    {
        workerPool.start(executor);
    }

    @Override
    public void halt()
    {
        workerPool.halt();
    }

    @Override
    public void markAsUsedInBarrier()
    {
        endOfChain = false;
    }

    @Override
    public boolean isRunning()
    {
        return workerPool.isRunning();
    }
}

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

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.lmax.disruptor.util.ThreadHints;

/**
 * 阻塞等待策略。
 * 通过lock等待【生产者】发布数据，可以达到较低的cpu开销。
 * 注意：有坑！Disruptor框架并没有消费者之间的协调策略，而是通过简单的忙等策略实现的，
 * 因此，如果前置消费者消费较慢，而后置消费者速度较快，使用该策略反而会导致极大的开销，这种情况下可使用{@link SleepingWaitStrategy}。
 *
 * Blocking strategy that uses a lock and condition variable for {@link EventProcessor}s waiting on a barrier.
 * <p>
 * This strategy can be used when throughput and low-latency are not as important as CPU resource.
 */
public final class BlockingWaitStrategy implements WaitStrategy
{
    private final Lock lock = new ReentrantLock();
    private final Condition processorNotifyCondition = lock.newCondition();

    @Override
    public long waitFor(long sequence, Sequence cursorSequence, Sequence dependentSequence, SequenceBarrier barrier)
        throws AlertException, InterruptedException
    {
        // 确保生产者已生产者该数据，这期间可能阻塞
        long availableSequence;
        if (cursorSequence.get() < sequence)
        {
            lock.lock();
            try
            {
                while (cursorSequence.get() < sequence)
                {
                    barrier.checkAlert();
                    processorNotifyCondition.await();
                }
            }
            finally
            {
                lock.unlock();
            }
        }

        // 等待前驱消费者消费完对应的事件，这是实现消费者之间happens-before的关键
        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            barrier.checkAlert();
            ThreadHints.onSpinWait();
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
        lock.lock();
        try
        {
            processorNotifyCondition.signalAll();
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public String toString()
    {
        return "BlockingWaitStrategy{" +
            "processorNotifyCondition=" + processorNotifyCondition +
            '}';
    }
}

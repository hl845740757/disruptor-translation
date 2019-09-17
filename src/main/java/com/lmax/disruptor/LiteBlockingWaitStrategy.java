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

import com.lmax.disruptor.util.ThreadHints;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 更轻量级的阻塞等待策略，注释参考{@link BlockingWaitStrategy}。
 * <p>
 * 特征：延迟较高、吞吐量也较低，但是CPU使用率极低，适合那些延迟和吞吐量并不太重要的场景，或CPU资源紧缺的场景。
 *
 * Variation of the {@link BlockingWaitStrategy} that attempts to elide conditional wake-ups when
 * the lock is uncontended.  Shows performance improvements on microbenchmarks.  However this
 * wait strategy should be considered experimental as I have not full proved the correctness of
 * the lock elision code.
 */
public final class LiteBlockingWaitStrategy implements WaitStrategy
{
    private final Object mutex = new Object();
    private final AtomicBoolean signalNeeded = new AtomicBoolean(false);


    @Override
    public long waitFor(long sequence, Sequence cursorSequence, Sequence dependentSequence, SequenceBarrier barrier)
        throws AlertException, InterruptedException
    {
        long availableSequence;
        // 确保生产者生产了对应的数据
        if (cursorSequence.get() < sequence)
        {
            synchronized (mutex)
            {
                do
                {
                	// 在这里做了一些优化，只有真正有线程等待时，才需要通知
                    signalNeeded.getAndSet(true);

                    if (cursorSequence.get() >= sequence)
                    {
                        break;
                    }

                    // 可理解为，在执行耗时操作之前检查中断(提高中断响应性)
                    barrier.checkAlert();
                    mutex.wait();
                }
                while (cursorSequence.get() < sequence);
            }
        }

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
        if (signalNeeded.getAndSet(false))
        {
            // 走到这里表示需要进行通知
            synchronized (mutex)
            {
                mutex.notifyAll();
            }
        }
    }

    @Override
    public String toString()
    {
        return "LiteBlockingWaitStrategy{" +
            "mutex=" + mutex +
            ", signalNeeded=" + signalNeeded +
            '}';
    }
}

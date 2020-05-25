package com.lmax.disruptor.dsl;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.WaitStrategy;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * 之所以不再推荐使用Executor的方式创建Disruptor，是因为外部的Executor可能是有界线程池，
 * 每一个{@link com.lmax.disruptor.EventProcessor} 都需要一个独立的线程。
 * 如果无法为EventProcessor创建足够多的线程，则会造成死锁(消费者总体进度为进度最小的那个---未运行的那些必定在列)，
 * 导致生产者阻塞，生产者阻塞又导致其它消费者阻塞 --> 死锁。
 * {@link Disruptor#Disruptor(EventFactory, int, Executor)}
 * {@link Disruptor#Disruptor(EventFactory, int, Executor, ProducerType, WaitStrategy)}
 *
 * {@link BasicExecutor} 看似无界线程池，但实际是有界线程池，因为EventProcessor的数量是固定的。
 * 创建的线程数取决于EventProcessor的数量。
 *
 * 且该线程池不会用到工作队列{@link java.util.concurrent.BlockingQueue}，而是通过{@link com.lmax.disruptor.RingBuffer}
 * 协调生产者与消费者之间的速度。
 */
public class BasicExecutor implements Executor
{
    private final ThreadFactory factory;
    private final Queue<Thread> threads = new ConcurrentLinkedQueue<>();

    public BasicExecutor(ThreadFactory factory)
    {
        this.factory = factory;
    }

	/**
	 * @param command 在disruptor中其实就是 {@link com.lmax.disruptor.EventProcessor}
	 */
    @Override
    public void execute(Runnable command)
    {
        final Thread thread = factory.newThread(command);
        if (null == thread)
        {
            throw new RuntimeException("Failed to create thread to run: " + command);
        }

        thread.start();

        threads.add(thread);
    }

    @Override
    public String toString()
    {
        return "BasicExecutor{" +
            "threads=" + dumpThreadInfo() +
            '}';
    }

    private String dumpThreadInfo()
    {
        final StringBuilder sb = new StringBuilder();

        final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

        for (Thread t : threads)
        {
            ThreadInfo threadInfo = threadMXBean.getThreadInfo(t.getId());
            sb.append("{");
            sb.append("name=").append(t.getName()).append(",");
            sb.append("id=").append(t.getId()).append(",");
            sb.append("state=").append(threadInfo.getThreadState()).append(",");
            sb.append("lockInfo=").append(threadInfo.getLockInfo());
            sb.append("}");
        }

        return sb.toString();
    }
}

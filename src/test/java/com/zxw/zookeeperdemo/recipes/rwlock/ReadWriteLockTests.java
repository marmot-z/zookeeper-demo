package com.zxw.zookeeperdemo.recipes.rwlock;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;

public class ReadWriteLockTests {

    private static int executedTests = 0;

    public static void main(String[] args) throws Exception {
        testHelper(ReadWriteLockTests::testReadLockShared);
        testHelper(ReadWriteLockTests::testWriteLockExclusive1);
        testHelper(ReadWriteLockTests::testWriteLockExclusive2);
        testHelper(ReadWriteLockTests::testWriteLockExclusive3);
        // TODO 测试先获取两个读锁，两个写锁，对其中一个写锁进行中断，来验证中断的正确性
    }

    private static void testHelper(BiConsumer<Lock, Lock> testFunction) {
        CuratorFramework curator = CuratorFrameworkFactory.newClient(
                "localhost:2181", new ExponentialBackoffRetry(1, 3));
        ReadWriteLock readWriteLock = new ReadWriteLock(curator, "/locks");
        Lock readLock = readWriteLock.readLock();
        Lock writeLock = readWriteLock.writeLock();

        curator.start();

        try {
            System.out.println("\n======= Test-" + (++executedTests) + " =======");
            testFunction.accept(readLock, writeLock);
        } finally {
            curator.close();
        }
    }

    private static void testReadLockShared(Lock readLock, Lock writeLock) {
        // 从 ReadWriteLock 中获取 readLock、writeLock
        final int threadCount = 5;
        CountDownLatch latch = new CountDownLatch(threadCount);

        // 多个 readLock 可以共享锁
        Runnable readRunnable = () -> {
            try {
                readLock.lock();
                try {
                    printWithMillis(Thread.currentThread().getName() + "获取到锁");
                    Thread.sleep(3_000);
                    latch.countDown();
                } finally {
                    printWithMillis(Thread.currentThread().getName() + "释放锁");
                    readLock.unlock();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(readRunnable);
            t.setName("read" + i + " thread");
            t.start();
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static void testWriteLockExclusive1(Lock readLock, Lock writeLock) {
        // 在使用 readLock 时，writeLock 无法使用
        // 在所有 readLock 释放完毕后，writeLock 才可以使用
        Thread t1 = new Thread(lockUseRunnable(readLock));
        t1.setName("read thread");
        t1.start();

        try {
            Thread.sleep(100);

            Thread t2 = new Thread(lockUseRunnable(writeLock));
            t2.setName("write thread");
            t2.start();

            t2.join();
        } catch (InterruptedException e) {
            Thread.interrupted();
            e.printStackTrace();
        }
    }

    private static void testWriteLockExclusive2(Lock readLock, Lock writeLock) {
        Thread t1 = new Thread(lockUseRunnable(writeLock));
        t1.setName("write thread");
        t1.start();

        try {
            Thread.sleep(100);

            Thread t2 = new Thread(lockUseRunnable(readLock));
            t2.setName("read thread");
            t2.start();

            t2.join();
        } catch (InterruptedException e) {
            Thread.interrupted();
            e.printStackTrace();
        }
    }

    private static void testWriteLockExclusive3(Lock readLock, Lock writeLock) {
        Thread t1 = new Thread(lockUseRunnable(readLock));
        Thread t2 = new Thread(lockUseRunnable(readLock));
        t1.setName("read thread 1");
        t1.start();
        t2.setName("read thread 2");
        t2.start();

        try {
            Thread.sleep(100);

            Thread t3 = new Thread(lockUseRunnable(writeLock));
            t3.setName("write thread");
            t3.start();

            Thread.sleep(100);

            Thread t4 = new Thread(lockUseRunnable(readLock));
            Thread t5 = new Thread(lockUseRunnable(readLock));
            t4.setName("read thread 4");
            t4.start();
            t5.setName("read thread 5");
            t5.start();

            t4.join();
            t5.join();
        } catch (InterruptedException e) {
            Thread.interrupted();
            e.printStackTrace();
        }
    }

    private static Runnable lockUseRunnable(Lock lock) {
        return () -> {
            try {
                lock.lock();
                try {
                    printWithMillis(Thread.currentThread().getName() + "获取到锁");
                    Thread.sleep(3_000);
                } finally {
                    printWithMillis(Thread.currentThread().getName() + "释放锁");
                    lock.unlock();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static void testLockInterruptibly(Lock readLock, Lock writeLock) {
        Thread t1 = new Thread(lockUseRunnable(readLock));
        t1.setName("read thread");
        t1.start();

        try {
            Thread.sleep(100);

            Thread t3 = new Thread(lockUseRunnable(writeLock));
            t3.setName("write thread");
            t3.start();

            // 等待线程被中断
            Thread.sleep(1000);
            t3.interrupt();

            t1.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static void printWithMillis(String str) {
        long millis = System.currentTimeMillis();

        System.out.println(millis + " > " + str);
    }
}

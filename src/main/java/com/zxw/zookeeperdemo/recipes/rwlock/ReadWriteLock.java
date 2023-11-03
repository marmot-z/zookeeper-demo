package com.zxw.zookeeperdemo.recipes.rwlock;


import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.apache.zookeeper.CreateMode.EPHEMERAL_SEQUENTIAL;

/**
 * 基于 zookeeper 实现的读写锁，支持以下特性：
 * 1. 共享读锁，独占写锁
 * 2. 支持可重入
 * 3. 锁的降级
 * 4. 等待超时的锁
 * 5. 可中断的锁
 */
public class ReadWriteLock {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadWriteLock.class);

    private final CuratorFramework curator;
    private final byte[] data = {0x12, 0x34};
    private final String dir;
    private final ReadLock readLock;
    private final WriteLock writeLock;

    public ReadWriteLock(CuratorFramework curator, String dir) {
        this.curator = Objects.requireNonNull(curator);
        this.dir = Objects.requireNonNull(dir);

        this.readLock = new ReadLock();
        this.writeLock = new WriteLock();
    }

    public Lock readLock() {
        return readLock;
    }

    public Lock writeLock() {
        return writeLock;
    }

    private abstract class AbstractLock implements Lock, Watcher {

        protected final Object mutex = new Object();
        /**
         * 确保每个线程都持有一个节点
         */
        protected ThreadLocal<ZNodeName> zNodeNameThreadLocal = new ThreadLocal<>();

        @Override
        public void lock() throws Exception {
            ZNodeName zNodeName = zNodeNameThreadLocal.get();

            // 避免唤醒后重新创建节点
            if (Objects.isNull(zNodeName)) {
                // 根据 sessionId 用以标识当前客户端
                String prefix = lockType().name() + "-";
                String path = ZKPaths.makePath(dir, prefix);

                // 创建顺序节点以及父目录
                String id = curator.create()
                        .creatingParentContainersIfNeeded()
                        .withProtection()
                        .withMode(EPHEMERAL_SEQUENTIAL)
                        .forPath(path, data);
                zNodeNameThreadLocal.set(zNodeName = new ZNodeName(id));
            }

            do {
                // 获取父目录下的子节点
                List<String> names = curator.getChildren().forPath(dir);
                SortedSet<ZNodeName> sortNodeNames = new TreeSet<>();

                // 将子节点进行排序
                for (String name : names) {
                    sortNodeNames.add(new ZNodeName(dir + "/" + name));
                }

                // 获取有序列表中的第一个节点，与自身进行比对，是则返回 true
                String ownerNodeName = sortNodeNames.first().getName();
                if (zNodeName.getName().equals(ownerNodeName)) {
                    break;
                }

                try {
                    if (acquireLock(sortNodeNames)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    // lockInterruptibly() 方法响应中断，并抛出中断异常
                    LOGGER.info("{} 线程被中断，退出锁等待", Thread.currentThread().getName());
                    Thread.interrupted();
                    clear();
                    break;
                }
            } while (true);
        }

        @Override
        public void unlock() throws Exception {
            clear();
        }

        private void clear() {
            // 删除当前节点，并清理状态
            String id = zNodeNameThreadLocal.get().getName();

            try {
                zNodeNameThreadLocal.remove();
                curator.delete().guaranteed().forPath(id);
            } catch (Exception e) {
                LOGGER.error("释放节点 {} 出现错误", id, e);
            }
        }

        @Override
        public void process(WatchedEvent watchedEvent) {
            synchronized (mutex) {
                mutex.notifyAll();
            }
        }

        protected abstract ZNodeName.NodeType lockType();

        protected abstract boolean acquireLock(SortedSet<ZNodeName> sortNodeNames) throws Exception;
    }

    class ReadLock extends AbstractLock {

        @Override
        protected ZNodeName.NodeType lockType() {
            return ZNodeName.NodeType.READ;
        }

        @Override
        protected boolean acquireLock(SortedSet<ZNodeName> sortNodeNames) throws Exception {
            // 对比自身小的节点，判断是否有 write 节点
            // 是则对前一个比自身小的 write 节点进行监听，并 mutex.wait()。当节点消失后回调，mutex.notify()
            // 没有，则获取锁成功
            ZNodeName idName = zNodeNameThreadLocal.get();
            SortedSet<ZNodeName> lessThanMe = sortNodeNames.headSet(idName);
            ZNodeName lastWriteZNodeName = null;
            Iterator<ZNodeName> iterator = lessThanMe.iterator();

            while (iterator.hasNext()) {
                ZNodeName zNodeName = iterator.next();

                if (zNodeName.getType().equals(ZNodeName.NodeType.WRITE)) {
                    lastWriteZNodeName = zNodeName;
                }
            }

            if (Objects.nonNull(lastWriteZNodeName)) {
                curator.checkExists().usingWatcher(this).forPath(lastWriteZNodeName.getName());

                synchronized (mutex) {
                    mutex.wait();
                }

                // 唤醒后重新竞争锁，避免虚假唤醒
                return false;
            }

            return true;
        }
    }

    class WriteLock extends AbstractLock {

        @Override
        protected ZNodeName.NodeType lockType() {
            return ZNodeName.NodeType.WRITE;
        }

        @Override
        protected boolean acquireLock(SortedSet<ZNodeName> sortNodeNames) throws Exception {
            // 对前一个节点进行监听，并 mutex.wait()
            // 当节点消失后回调，mutex.notify()
            ZNodeName idName = zNodeNameThreadLocal.get();
            SortedSet<ZNodeName> lessThanMe = sortNodeNames.headSet(idName);
            if (!lessThanMe.isEmpty()) {
                String lastZNodeName = lessThanMe.last().getName();

                curator.checkExists().usingWatcher(this).forPath(lastZNodeName);

                synchronized (mutex) {
                    mutex.wait();
                }

                // 唤醒后重新竞争锁，避免虚假唤醒
                return false;
            }

            return true;
        }
    }
}

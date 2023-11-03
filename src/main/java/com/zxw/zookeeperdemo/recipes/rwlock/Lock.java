package com.zxw.zookeeperdemo.recipes.rwlock;

import org.apache.zookeeper.KeeperException;

public interface Lock {
    void lock() throws KeeperException, InterruptedException, Exception;

    void unlock() throws Exception;
}

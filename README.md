## What is zookeeper？

什么是 Zookeeper？官方给它的定义是提供配置中心、命名服务、分布式同步以及协调的中心服务。仅通过类 Unix 的文件组织数据结构以及监听通知机制就可以实现分布式协调、同步、配置等功能。
## Concept

在 zookeeper 中有四种节点：
- 持久化节点，节点在 session 无效或者集群重启后仍会存在
- 持久化顺序节点，保证持久化节点按照 zookeeper 接收请求的顺序进行排序
- 临时节点，session 无效后，该节点被删除
- 临时顺序节点，保证临时节点按照 zookeeper 接收请求的顺序进行排序

### Session

每个客户端和集群的一个连接是一个 session，通过心跳判断 session 是否有效。若干时间内心跳检测失败，则 session 失效。

## Quick start

在 [apache zookeeper 官网](https://zookeeper.apache.org/releases.html)上下载最新发行版。

使用 `bin/zkServer.sh start`命令启动服务端，使用 `bin/zkCli.sh` 命令启动客户端连接服务端。zookeeper 支持的操作命令如下：
```bash
[zk: localhost:2181(CONNECTED) 0] help
ZooKeeper -server host:port -client-configuration properties-file cmd args
	addWatch [-m mode] path # optional mode is one of [PERSISTENT, PERSISTENT_RECURSIVE] - default is PERSISTENT_RECURSIVE
	addauth scheme auth
	close
	config [-c] [-w] [-s]
	connect host:port
	create [-s] [-e] [-c] [-t ttl] path [data] [acl]
	delete [-v version] path
	deleteall path [-b batch size]
	delquota [-n|-b|-N|-B] path
	get [-s] [-w] path
	getAcl [-s] path
	getAllChildrenNumber path
	getEphemerals path
	history
	listquota path
	ls [-s] [-w] [-R] path
	printwatches on|off
	quit
	reconfig [-s] [-v version] [[-file path] | [-members serverID=host:port1:port2;port3[,...]*]] | [-add serverId=host:port1:port2;port3[,...]]* [-remove serverId[,...]*]
	redo cmdno
	removewatches path [-c|-d|-a] [-l]
	set [-s] [-v version] path data
	setAcl [-s] [-v version] [-R] path acl
	setquota -n|-b|-N|-B val path
	stat [-w] path
	sync path
	version
	whoami
Command not found: Command not found help
```

## Zookeeper recipes

zookeeper 的典型应用场景有：

- 分布式栅栏 （distributed barrier）
- 分布式队列（distributed queue）
- 分布式锁（distributed lock）
- 选举（electron）
- 服务发现（service discovery）
- ...

这些例子大部分都可以在官网 [Receipes](https://zookeeper.apache.org/doc/current/recipes.html) 章节中找到相关介绍，通过了解这些案例我们也可以学习如何使用 zookeeper 来完成一些分布式任务。

### Barrier

Barrier 可以使得分布式的应用同步开始和技术执行对应的任务。

#### enter

大致逻辑如下：
- 应用在根目录下创建临时节点
- 获取根目录下子节点的个数
- 如果小于 Barrier 阈值，则 mutex 锁等待（在上一步获取根目录下子节点方法中设置了 Watcher，当子节点个数发生变化时触发 Watcher，唤醒 mutex 锁）
- 如果等于 Barrier 阈值，则说明满足执行条件

``` java
/**
 * Join barrier
 *
 * @return
 * @throws KeeperException
 * @throws InterruptedException
 */
boolean enter() throws KeeperException, InterruptedException{
	zk.create(root + "/" + name, new byte[0], Ids.OPEN_ACL_UNSAFE,
			CreateMode.EPHEMERAL);
	while (true) {
		synchronized (mutex) {
		    // 此处设置了 Watcher，当子节点发生变化触发 Watcher，mutex.notify()
			List<String> list = zk.getChildren(root, true);

			if (list.size() < size) {
				mutex.wait();
			} else {
				return true;
			}
		}
	}
}
```

#### leave

大致逻辑如下：
- 删除本节点
- 获取根目录下的子节点个数
- 如果不为 0 则等待（当节点个数发生时进行唤醒）
- 如果为 0 则退出成功

``` java
/**
 * Wait until all reach barrier
 *
 * @return
 * @throws KeeperException
 * @throws InterruptedException
 */
boolean leave() throws KeeperException, InterruptedException{
	zk.delete(root + "/" + name, 0);
	while (true) {
		synchronized (mutex) {
			List<String> list = zk.getChildren(root, true);
				if (list.size() > 0) {
					mutex.wait();
				} else {
					return true;
				}
			}
		}
	}
}
```

### Queue

分布式的生产者-消费者队列。

生产者在指定根目录下创建顺序临时节点，消费者读取根目录下的所有子节点，找到最小的子节点（返回的子节点列表并不是顺序的，去除前缀然后按照计数值进行排序找到最小值）进行消费（读取节点数据，并删除该节点）。

#### produce

- 在根目录下创建新的顺序临时节点

``` java
/**
 * Add element to the queue.
 *
 * @param i
 * @return
 */
boolean produce(int i) throws KeeperException, InterruptedException{
    ByteBuffer b = ByteBuffer.allocate(4);
    byte[] value;

    // Add child with value i
    b.putInt(i);
    value = b.array();
    zk.create(root + "/element", value, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT_SEQUENTIAL);

    return true;
}
```

#### consume

- 读取根目录下的子节点列表
- 如果子节点列表为空，则等待（当子节点列表发生变化时唤醒）
- 找到子节点列表最小序列的节点，读取节点数据然后删除

``` java
/**
 * Remove first element from the queue.
 *
 * @return
 * @throws KeeperException
 * @throws InterruptedException
 */
int consume() throws KeeperException, InterruptedException{
    int retvalue = -1;
    Stat stat = null;

    // Get the first element available
    while (true) {
        synchronized (mutex) {
            List<String> list = zk.getChildren(root, true);
            if (list.size() == 0) {
                System.out.println("Going to wait");
                mutex.wait();
            } else {
                Integer min = new Integer(list.get(0).substring(7));
                for(String s : list){
                    Integer tempValue = new Integer(s.substring(7));
                    //System.out.println("Temporary value: " + tempValue);
                    if(tempValue < min) min = tempValue;
                }
                System.out.println("Temporary value: " + root + "/element" + min);
                byte[] b = zk.getData(root + "/element" + min,
                            false, stat);
                zk.delete(root + "/element" + min, 0);
                ByteBuffer buffer = ByteBuffer.wrap(b);
                retvalue = buffer.getInt();

                return retvalue;
                }
            }
        }
    }
}
```

示例代码：https://github.com/apache/zookeeper/tree/master/zookeeper-recipes/zookeeper-recipes-queue

### WriteLock

分布式互斥写锁。多个应用之间在进行全局的同步操作时需要分布式锁。

- 在指定目录下创建顺序临时节点
- 获取目录下节点列表
- 判断最小序列的子节点是否为本身，是则获取锁成功，退出
- 最小序列节点不是本身，找到比自身序列小的子节点列表中最大序列的节点（即前一个节点），在其上注册 Watcher，监听其状态
- 当前一个节点删除后，触发 Watcher 重新进入获取锁的逻辑，直到获取到锁

``` java
public boolean execute() throws KeeperException, InterruptedException {
    do {
        if (id == null) {
            long sessionId = zookeeper.getSessionId();
            String prefix = "x-" + sessionId + "-";
            // lets try look up the current ID if we failed
            // in the middle of creating the znode
            findPrefixInChildren(prefix, zookeeper, dir);
            idName = new ZNodeName(id);
        }
        List<String> names = zookeeper.getChildren(dir, false);
        if (names.isEmpty()) {
            LOG.warn("No children in: {} when we've just created one! Lets recreate it...", dir);
            // lets force the recreation of the id
            id = null;
        } else {
            // lets sort them explicitly (though they do seem to come back in order ususally :)
            SortedSet<ZNodeName> sortedNames = new TreeSet<>();
            for (String name : names) {
                sortedNames.add(new ZNodeName(dir + "/" + name));
            }
            ownerId = sortedNames.first().getName();
            SortedSet<ZNodeName> lessThanMe = sortedNames.headSet(idName);
            if (!lessThanMe.isEmpty()) {
                ZNodeName lastChildName = lessThanMe.last();
                lastChildId = lastChildName.getName();
                LOG.debug("Watching less than me node: {}", lastChildId);
                Stat stat = zookeeper.exists(lastChildId, new LockWatcher());
                if (stat != null) {
                    return Boolean.FALSE;
                } else {
                    LOG.warn("Could not find the stats for less than me: {}", lastChildName.getName());
                }
            } else {
                if (isOwner()) {
                    LockListener lockListener = getLockListener();
                    if (lockListener != null) {
                        lockListener.lockAcquired();
                    }
                    return Boolean.TRUE;
                }
            }
        }
    }
    while (id == null);
    return Boolean.FALSE;
}
```

### election

主从选举。在主从架构中的项目里，leader 下线后，各个 follower 选举出新的 leader。

步骤如下：
1. 各个应用到指定目录下（如：/election）创建临时顺序节点
2. 应用获取该目录下的子节点列表
3. 将子节点进行排序，判断当前应用创建的子节点在列表中的位置
4. 如果应用创建的子节点顺序最小，则代表本应用是 leader，选举流程结束
5. 如果应该创建的子节点顺序不是最小，则对上一个子节点进行监听，当上一个子节点销毁的时候，触发当前应用的选举流程


判断是否为 leader（各个应用执行该方法的时机和顺序是独立的，其通过目录下子节点的顺序这个共识来判断选举结果）
``` java
private void determineElectionStatus() throws KeeperException, InterruptedException {  
  
    state = State.DETERMINE;  
    dispatchEvent(EventType.DETERMINE_START);  
  
    LeaderOffer currentLeaderOffer = getLeaderOffer();  
  
    String[] components = currentLeaderOffer.getNodePath().split("/");  
  
    currentLeaderOffer.setId(Integer.valueOf(components[components.length - 1].substring("n_".length())));  
  
    List<LeaderOffer> leaderOffers = toLeaderOffers(zooKeeper.getChildren(rootNodeName, false));  
  
    /*  
     * For each leader offer, find out where we fit in. If we're first, we     * become the leader. If we're not elected the leader, attempt to stat the     * offer just less than us. If they exist, watch for their failure, but if     * they don't, become the leader.     */    for (int i = 0; i < leaderOffers.size(); i++) {  
        LeaderOffer leaderOffer = leaderOffers.get(i);  
  
        if (leaderOffer.getId().equals(currentLeaderOffer.getId())) {  
            LOG.debug("There are {} leader offers. I am {} in line.", leaderOffers.size(), i);  
  
            dispatchEvent(EventType.DETERMINE_COMPLETE);  
  
            if (i == 0) {  
                becomeLeader();  
            } else {  
                becomeReady(leaderOffers.get(i - 1));  
            }  
  
            /* Once we've figured out where we are, we're done. */  
            break;  
        }  
    }  
}
```

成为 follower 节点时，对上一个节点注册 Watcher（避免羊群效应）
``` java
private void becomeReady(LeaderOffer neighborLeaderOffer)  
    throws KeeperException, InterruptedException {  
  
    LOG.info(  
        "{} not elected leader. Watching node: {}",  
        getLeaderOffer().getNodePath(),  
        neighborLeaderOffer.getNodePath());  
  
    /*  
     * Make sure to pass an explicit Watcher because we could be sharing this     * zooKeeper instance with someone else.     */    Stat stat = zooKeeper.exists(neighborLeaderOffer.getNodePath(), this);  
  
    if (stat != null) {  
        dispatchEvent(EventType.READY_START);  
        LOG.debug(  
            "We're behind {} in line and they're alive. Keeping an eye on them.",  
            neighborLeaderOffer.getNodePath());  
        state = State.READY;  
        dispatchEvent(EventType.READY_COMPLETE);  
    } else {  
        /*  
         * If the stat fails, the node has gone missing between the call to         * getChildren() and exists(). We need to try and become the leader.         */        LOG.info(  
            "We were behind {} but it looks like they died. Back to determination.",  
            neighborLeaderOffer.getNodePath());  
        determineElectionStatus();  
    }  
  
}
```

当上一个节点销毁时，触发 Watcher 逻辑，重新开始选举
``` java
@Override  
public void process(WatchedEvent event) {  
    if (event.getType().equals(Watcher.Event.EventType.NodeDeleted)) {  
        if (!event.getPath().equals(getLeaderOffer().getNodePath())  
            && state != State.STOP) {  
            LOG.debug(  
                "Node {} deleted. Need to run through the election process.",  
                event.getPath());  
            try {  
                determineElectionStatus();  
            } catch (KeeperException | InterruptedException e) {  
                becomeFailed(e);  
            }  
        }  
    }  
}
```

### Service Discovery

在分布式应用中，服务发现用于找到各个应用服务，以互相调用，或者对外提供服务。

服务的注册路径格式大概如下：
```
base path
       |_______ service A name
                    |__________ instance 1 id --> (serialized ServiceInstance)
                    |__________ instance 2 id --> (serialized ServiceInstance)
                    |__________ ...
       |_______ service B name
                    |__________ instance 1 id --> (serialized ServiceInstance)
                    |__________ instance 2 id --> (serialized ServiceInstance)
                    |__________ ...
       |_______ ...
```

基本思路：
- 在 base path 下创建 /service x 目录，代表 x 服务
- 然后在 /service x 目录下注册对应的服务实例，代表一个对外提供服务的实例（多实例的情况下则会创建多个节点）
- 服务发现方读取对应的 /base path/service x 目录读取服务 x 的实例信息
- 读取服务实例信息的地址，访问对应的服务

相关代码：curator-x-discover
使用案例：
- `org.apache.curator.x.discovery.ServiceCacheLeakTester`
- `discovery.DiscoveryExample`

### 相关技巧

在使用 zookeeper 来构建分布式任务时，不同于编写单机任务，我们要考虑当前不同机器的状态，以及它们可能出现的情况，并进行重试、通知以及其他处理。

#### zookeeper 异步 API 构建同步方法

当构建的方法不满足执行条件时（如 Barrier.enter 方法），应用通过一个 mutex 本地锁变量使得当前线程进行等待，并注册 Watcher。当条件发生变化时触发 Watcher，唤醒 mutex 阻塞的线程，判断当前条件是否满足执行，是则继续执行，否则继续阻塞等待。

**<u>使用 while 循环的方式重复检查状态，防止错误唤醒后退出主逻辑</u>**。

#### 可恢复的异常

创建节点时服务端创建成功后，但尚未给用户返回创建结果就宕机了。当重新连接后，session 仍有效时，客户端无法判断此节点是否创建成功（这与 zookeeper 的实现机制有关）。

这个时候客户端应该获取子节点列表，判断子节点中是否有自己创建的子节点，如果有则不再继续创建，无则重新创建。

``` java
private void findPrefixInChildren(String prefix, ZooKeeper zookeeper, String dir)
    throws KeeperException, InterruptedException {
    // 判断子节点列表中是否已经有自己创建的子节点
    List<String> names = zookeeper.getChildren(dir, false);
    for (String name : names) {
        // 如果有则不再创建
        if (name.startsWith(prefix)) {
            id = name;
            LOG.debug("Found id created last time: {}", id);
            break;
        }
    }
    // 无则继续创建
    if (id == null) {
        id = zookeeper.create(dir + "/" + prefix, data, getAcl(), EPHEMERAL_SEQUENTIAL);

        LOG.debug("Created id: {}", id);
    }

}
```

#### 避免羊群效应（herd effect）

在一些情况中，如果一个节点上注册了众多的 Watcher，当节点触发事件后，会有众多客户端触发 Watcher 回调，这会导致网络庸俗，也不利于项目集群扩展。所以在实现 WriteLock 时，只让后一个节点 Watch 前一个节点，避免每个节点的删除触发所有客户端的 Watcher 回调。

比如：全局锁、选举时，每个节点在上一个节点上注册 Watcher。

## Apache curator

在使用 zookeeper 提供的原生 java API 时，其 API 不够简洁，有许多特定问题需要自行处理。Apache curator 是基于 zookeeper java API 封装的高级 API。使用该 library 可以简化 zookeeper 开发流程，使开发者专注于特定业务逻辑。

下面是使用 curator 开发的一款分布式读写锁。com.zxw.zookeeperdemo.recipes.rwlock.ReadWriteLock


## 参考文档
市面上有些其他文档和视频，不过与官网上的内容大同小异，直接阅读官网内容，反而更加规范和丰富。
- https://zookeeper.apache.org
- https://curator.apache.org/docs/about
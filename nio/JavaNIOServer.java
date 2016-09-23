package nio;
/*
* 服务线程启动后，监听指定端口，等待客户端请求的到来，然后NioTcpClient客户端进程启动并发送请求数据，服务端接收到请求数据后，响应客户端（将请求的数据作为响应数据写回到客户端通道SocketChannel，并等待客户端处理）。
* */
/**
 * Created by Oo潇锋Oo on 2016/9/19.
 */
/*
服务端

传建一个 Selector 实例；

将其注册到各种信道，并指定每个信道上感兴趣的I/O操作；

重复执行：

调用一种 select()方法；

获取选取的键列表；

对于已选键集中的每个键：

获取信道，并从键中获取附件（如果为信道及其相关的 key 添加了附件的话）；

确定准备就绪的操纵并执行，如果是 accept 操作，将接收的信道设置为非阻塞模式，并注册到选择器；

如果需要，修改键的兴趣操作集；

从已选键集中移除键。
客户端

与基于多线程的 TCP 客户端大致相同，只是这里是通过信道建立的连接，但在等待连接建立及读写时，我们可以异步地执行其他任务。

*/

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JavaNIOServer {
//支持获取的完全并发和更新的所期望可调整并发的哈希表
    private Map<Integer, Integer> rejectedThreadCountMap = new ConcurrentHashMap<>();
/*
* 当 execute 不能接受某个任务时，可以由 ThreadPoolExecutor 调用的方法。
* 因为超出其界限而没有更多可用的线程或队列槽时，或者关闭 Executor 时就可能发生这种情况。
在没有其他替代方法的情况下，该方法可能抛出未经检查的 RejectedExecutionException，
而该异常将传播到 execute 的调用者。
*/
    private RejectedExecutionHandler rejectedExecutionHandler = new RejectedExecutionHandler() {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            try {
                TimeUnit.MILLISECONDS.sleep(100);
                int hashcode = r.hashCode();
                Integer count = rejectedThreadCountMap.get(hashcode);
                if (count == null) {
                    count = 0;
                    rejectedThreadCountMap.put(hashcode, count);
                } else {
                    count++;
                    rejectedThreadCountMap.put(hashcode, count);
                }
                if (count < 1) {
                    executor.execute(r);
                } else {
                    if (r instanceof WriteClientSocketHandler) {
                        WriteClientSocketHandler realThread = (WriteClientSocketHandler) r;
                        System.out.println("服务系统繁忙,客户端WriteClientSocketHandler[" + realThread.client + "]请求被拒绝处理！");
                        SelectionKey selectionKey = realThread.client.keyFor(selector);
                        if (selectionKey != null) {
                            selectionKey.cancel();
                        }
                        if (realThread.client != null) {
                            try {
                                realThread.client.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        AtomicBoolean isWriting = isWritingMap.get(realThread.client);
                        isWriting.set(false);
                    } else if (r instanceof ReadClientSocketHandler) {
                        ReadClientSocketHandler realThread = (ReadClientSocketHandler) r;
                        System.out.println("服务系统繁忙,客户端ReadClientSocketHandler[" + realThread.client + "]请求被拒绝处理！");
                        SelectionKey selectionKey = realThread.client.keyFor(selector);
                        if (selectionKey != null) {
                            selectionKey.cancel();
                        }
                        if (realThread.client != null) {
                            try {
                                realThread.client.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        AtomicBoolean isReading = isReadingMap.get(realThread.client);
                        isReading.set(false);
                    } else {
                        System.out.println("服务系统繁忙,系统线程[" + r.getClass().getName() + "]被拒绝处理！");
                    }
                }
            } catch (InterruptedException e) {
                System.out.println("RejectedExecutionHandler处理发生异常：" + e.getMessage());
            }
        }
    };
    /*
    * corePoolSize - 池中所保存的线程数，包括空闲线程。
maximumPoolSize - 池中允许的最大线程数。
keepAliveTime - 当线程数大于核心时，此为终止前多余的空闲线程等待新任务的最长时间。
unit - keepAliveTime 参数的时间单位。
workQueue - 执行前用于保持任务的队列。此队列仅由保持 execute 方法提交的 Runnable 任务。
handler - 由于超出线程范围和队列容量而使执行被阻塞时所使用的处理程序。

    * */

    private ThreadPoolExecutor threadPool = new ThreadPoolExecutor(30, 50, 300, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(5), rejectedExecutionHandler);

    private Map<SocketChannel, AtomicBoolean> isReadingMap = new ConcurrentHashMap<SocketChannel, AtomicBoolean>();
    private Map<SocketChannel, AtomicBoolean> isWritingMap = new ConcurrentHashMap<SocketChannel, AtomicBoolean>();

    private Selector selector = null;
    private ServerSocketChannel ss = null;
    private volatile boolean isClose = false;
    //线程直接从内存读不从寄存器读，当一个线程修改一个共享变量时，另外一个线程能读到这个修改的值。

    /**
     * 创建非阻塞服务器绑定5555端口
     */
    public JavaNIOServer() {
        try {
            /*
            * 　　首先，服务端打开一个通道（ServerSocketChannel），并向通道中注册一个通道调度器（Selector）；
            * 然后向通道调度器注册感兴趣的事件SelectionKey(如：OP_ACCEPT)，接着就可以使用通道调度器（Selector）轮询通道
            * （ServerSocketChannel）上注册的事件，并进行相应的处理。
            * */
            ss = ServerSocketChannel.open();
            ss.bind(new InetSocketAddress(5555));//绑定端口
            ss.configureBlocking(false);//设置非阻塞
            selector = Selector.open();
            ss.register(selector, SelectionKey.OP_ACCEPT);
        } catch (Exception e) {
            e.printStackTrace();
            close();
        }
    }

    public boolean isClose() {
        return isClose;
    }

    /**
     * 关闭服务器
     */
    private void close() {
        isClose = true;
        threadPool.shutdown();
        try {
            if (ss != null) {
                ss.close();
            }
            if (selector != null) {
                selector.close();
            }
        } catch (IOException e) {
            System.out.println("服务器关闭发生异常：" + e.getMessage());
        }
    }

    /**
     * 启动选择器监听客户端事件
     */
    private void start() {
        threadPool.execute(new SelectorGuardHandler());
    }

    private class SelectorGuardHandler implements Runnable {

        @Override
        public void run() {

            while (!isClose) {
                try {
                    if (selector.select(10) == 0) {
                        //非阻塞可以做别的
                        continue;
                    }
                    //获取selector中的迭代器，选中项为注册的事件
                    Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                    // 遍历这个已选择的键集合来访问就绪的通道
                    while (iterator.hasNext()) {
                        SelectionKey selectedKey = iterator.next();
                        iterator.remove(); //删除已选key，防止重复处理
                        try {
                            if (selectedKey.isReadable()) {//有可读数据事件
                                //获取客户端传输数据可读取消息通道。
                                SocketChannel socketChannel = (SocketChannel) selectedKey.channel();
                                AtomicBoolean isReading = isReadingMap.get(socketChannel);
                                if (isReading == null) {
                                    isReading = new AtomicBoolean(false);
                                    isReadingMap.put(socketChannel, isReading);
                                }
                                while (isReading.get()) {
                                    Thread.sleep(5);//发现正在读则让其休息下
                                }
                                isReading.set(true);
                                threadPool.execute(new ReadClientSocketHandler(socketChannel));
                            } else if (selectedKey.isWritable()) {
                                Object responseMessage = selectedKey.attachment();//获取当前附加对象
                                SocketChannel socketChannel = (SocketChannel) selectedKey.channel();
                                selectedKey.interestOps(SelectionKey.OP_READ);//将此键的 interest 集合设置为给定值。
                                threadPool.execute(new WriteClientSocketHandler(socketChannel, responseMessage));
                            } else if (selectedKey.isAcceptable()) {//客户端请求连接事件
                                ServerSocketChannel ssc = (ServerSocketChannel) selectedKey.channel();
                                SocketChannel clientSocket = ssc.accept();//获得客户端连接通道
                                clientSocket.configureBlocking(false);
                                //在与客户端连接成功后，为客户端通道注册SelectionKey.OP_READ事件。
                                clientSocket.register(selector, SelectionKey.OP_READ);
                            }
                        } catch (CancelledKeyException e) {
                            selectedKey.cancel();//请求取消此键的通道到其选择器的注册。
                            System.out.println("服务器启动或运行发生异常：" + e);
                        }

                    }
                } catch (Exception e) {
                    if (e instanceof NullPointerException) {
                        e.printStackTrace();
                        System.out.println(e);
                        close();
                    } else {
                        System.out.println("服务器启动或运行发生异常：" + e);
                        close();
                    }
                    break;
                }
            }
        }
    }

    /**
     * 响应数据给客户端线程
     *
     * @author haoguo
     *
     */

    //没有进行什么现在，因为没有读就没有写，写在读那里注册
    private class WriteClientSocketHandler implements Runnable {
        private SocketChannel client;

        private Object responseMessage;

        private WriteClientSocketHandler(SocketChannel client, Object responseMessage) {
            this.client = client;
            this.responseMessage = responseMessage;
        }

        @Override
        public void run() {
            try {
                byte[] responseByteData = null;
                String logResponseString = "";
                if (responseMessage instanceof byte[]) {
                    responseByteData = (byte[]) responseMessage;
                    logResponseString = new String(responseByteData);
                } else if (responseMessage instanceof String) {
                    logResponseString = (String) responseMessage;
                    responseByteData = logResponseString.getBytes();
                } else if (responseMessage != null) {
                    System.out.println("不支持的数据类型" + responseMessage.getClass());
                    return;
                }
                if (responseByteData == null || responseByteData.length == 0) {
                    System.out.println("服务器响应的数据为空");
                    return;
                }
                // 一旦读完了所有的数据，就需要清空缓冲区，让它可以再次被写入。有两种方式能清空缓冲区：调用clear()或compact()方法。

                ByteBuffer data = ByteBuffer.allocate(responseByteData.length + 4);//设置缓存capacity
                data.putInt(responseByteData.length);
                data.put(responseByteData);
                data.flip();
                while (data.hasRemaining()) {
                    client.write(data);//从缓存写进通道
                }
                System.out.println("server响应客户端[" + client.getRemoteAddress() + "]数据 :["
                        + new String(logResponseString) + "]");
            } catch (Exception e) {
                try {
                    System.out.println("server响应客户端[" + client.getRemoteAddress() + "]数据 异常[" + e.getMessage() + "]");
                    SelectionKey selectionKey = client.keyFor(selector);
                    if (selectionKey != null) {
                        selectionKey.cancel();
                    }
                    if (client != null) {
                        client.close();
                    }
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            } finally {
                AtomicBoolean isWriting = isWritingMap.get(client);
                isWriting.set(false);
            }
        }

    }

    /**
     * 读客户端发送数据线程
     *
     * @author haoguo
     *
     */
    private class ReadClientSocketHandler implements Runnable {
        private SocketChannel client;
        private ByteBuffer dataLen = ByteBuffer.allocate(4);//分配多少capacity

        private ReadClientSocketHandler(SocketChannel client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                dataLen.clear();
                int len = 4;
                while (len > 0) {
                    int readLen = client.read(dataLen);
                    if (readLen == -1) {//表示读到底了
                        throw new Exception("读取客户端的长度readLen==" + readLen);
                    }
                    len -= readLen;
                }
                int data_length = dataLen.getInt(0);
                ByteBuffer data = ByteBuffer.allocate(data_length);
                while (data.hasRemaining()) {
                    client.read(data);
                }
                String readData = new String(data.array());
                System.out.println(Thread.currentThread().getId() + "服务器接收到客户端[" + client.getRemoteAddress() + "]数据 :["
                        + readData + "]");


                // dosomthing
                byte[] response = ("response" + readData.substring(0, 3)).getBytes();
                AtomicBoolean isWriting = isWritingMap.get(client);//取出isWriting真假
                if (isWriting == null) {
                    isWriting = new AtomicBoolean(false);
                    isWritingMap.put(client, isWriting);//是null则设置K,V. v=false
                }
                while (isWriting.get()) {
                    Thread.sleep(5);//such isWriting is true ,else sleep wait some time let it writing
                }
                isWriting.set(true);//读完了，设置writing 并注册其有写的权限事件，
                client.register(selector, SelectionKey.OP_WRITE, response);
            } catch (Exception e) {
                try {
                    SelectionKey selectionKey = client.keyFor(selector);
                    if (selectionKey != null) {
                        selectionKey.cancel();
                    }
                    System.out.println("客户端[" + client + "]关闭了连接,原因[" + e + "]");
                    if (client != null) {
                        client.close();
                    }

                } catch (IOException e1) {
                    System.out.println("客户端[" + client + "]关闭异常" + e1.getMessage());
                }
            } finally {
                AtomicBoolean isReading = isReadingMap.get(client);
                isReading.set(false);
            }

        }

    }

    public static void main(String[] args) {
        JavaNIOServer server = new JavaNIOServer();
        server.start();
    }
}
/*
* 1.channal：
有socketchanal等几种，通过TCP/ip 协议链接。



2.阻塞的含义
阻塞在哪里 相对于IO。。非阻塞在哪里：

针对于读写的请求，其中I/O在读写请求时候会阻塞住，其中这个线程会阻塞住，直到读完写完。
NIO在这些请求则会非阻塞，有数据我就读没数据读我可以干别的东西，写同样。。

3.这段程序有多少种线程，几种类型线程：
四种类型：一读二写三轮询四处理线程和队列超出的处理线程


4.有没有同步问题：

读的时候没有写则设置让写，写时候则等读完再写。
写的时候只要读完了立马写。其它读线程全部阻塞
看代码时候多问几个为什么
* */
package nio;
//很多个client
/*
* 客户端只是简单地发送请求数据和读响应数据。
* */
/**
 * Created by Oo潇锋Oo on 2016/9/19.
 */
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class JavaNIOClient {
    /*
    * FileChannel 从文件中读写数据。

DatagramChannel 能通过UDP读写网络中的数据。

SocketChannel 能通过TCP读写网络中的数据。

ServerSocketChannel可以监听新进来的TCP连接，像Web服务器那样。对每一个新进来的连接都会创建一个SocketChannel。
    * */
    private SocketChannel client; // 与服务器通信的信道
    private Selector selector = getSelector();// 信道选择器
    private ThreadPoolExecutor threadPool = new ThreadPoolExecutor(5, 10, 200, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(20));

    private volatile boolean isClose = false;
/*
* 在多线程环境下，当有多个线程同时执行这些类的实例包含的方法时，具有排他性，
* 即当某个线程进入方法，执行其中的指令时，不会被其他线程打断，而别的线程就像自旋锁一样，
* 一直等到该方法执行完成，才由JVM从等待队列中选择一个另一个线程进入，这只是一种逻辑上的理解。
* 实际上是借助硬件的相关指令来实现的，不会阻塞线程(或者说只是在硬件级别上阻塞了)。可以对基本数据、
* 数组中的基本数据、对类中的基本数据进行操作。原子变量类相当于一种泛化的volatile变量，
* 能够支持原子的和有条件的读-改-写操作。
* 并非所有线程安全都可以用这样的方法来实现，这只适合一些粒度比较小，
* 型如计数器这样的需求用起来才有效，否则也不会有锁的存在了。
* */
    private AtomicLong writeCount = new AtomicLong(0);
    private AtomicLong readCount = new AtomicLong(0);

    private AtomicBoolean isWriting = new AtomicBoolean(false);
    private AtomicBoolean isReading = new AtomicBoolean(false);

    public Selector getSelector() {
        try {
            return Selector.open(); // 获得一个通道管理器
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public JavaNIOClient() {
        try {
            client = SocketChannel.open(); // 获得一个Socket通道
            client.configureBlocking(false);  // 设置通道为非阻塞
            // 客户端连接服务器,其实方法执行并没有实现连接，需要在listen（）方法中调
            //用channel.finishConnect();才能完成连接
            client.connect(new InetSocketAddress("127.0.0.1", 5555));
            // 打开一个SocketChannel并连接到互联网上的某台服务器。
// 一个新连接到达ServerSocketChannel时，会创建一个SocketChannel。
            client.register(selector, SelectionKey.OP_CONNECT); //将通道管理器和该通道绑定，并为该通道注册SelectionKey.OP_CONNECT事件。
        } catch (IOException e) {
            System.out.println("创建客户端连接异常Client2" + e.getMessage());
            close();
        }

    }

    public void start() {
        threadPool.execute(new SelectorGuardHandler());
    }

    private class SelectorGuardHandler implements Runnable {

        @Override
        public void run() {
            // 轮询访问selector
            while (!isClose) {
                try {
                    //如果队列有新的Channel加入，那么Selector.select()会被唤醒
                    if (selector.select(10) == 0) {
                        //在等待信道准备的同时，也可以异步地执行其他任务，
                        continue;
                    }
                    // 获得selector中选中的项的迭代器
                    Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                    while (iterator.hasNext()) {
                        SelectionKey selectionKey = iterator.next();
                        // 删除已选的key,以防重复处理
                        iterator.remove();
                        // 连接事件发生
                        if (selectionKey.isReadable()) {
                            SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
                            if (isReading.get()) {//返回当前值。
                                Thread.sleep(5);
                            } else {
                                isReading.set(true);
                                threadPool.execute(new ReceiveMessageHandler(socketChannel));
                            }

                        } else if (selectionKey.isWritable()) {
                            Object requestMessage = selectionKey.attachment();//获取当前的附加对象
                            SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
                            selectionKey.interestOps(SelectionKey.OP_READ);
                            threadPool.execute(new SendMessageHandler(socketChannel, requestMessage));
                            // 如果正在连接，则完成连接
                        } else if (selectionKey.isConnectable()) {
                            SocketChannel sc = (SocketChannel) selectionKey.channel();
                            sc.finishConnect();
                            //在和服务端连接成功之后，为了可以接收到服务端的信息，需要给通道设置读的权限。
                            sc.register(selector, SelectionKey.OP_READ);
                        }
                    }
                } catch (Exception e) {
                    System.out.println("客户端启动或运行异常[start]：" + e.getMessage());
                    close();
                }
            }
        }
    }

    /**
     * 发送数据线程
     *
     * @author haoguo
     *
     */
    private class SendMessageHandler implements Runnable {
        private SocketChannel client;

        private Object requestMessage;

        private SendMessageHandler(SocketChannel client, Object requestMessage) {
            this.client = client;
            this.requestMessage = requestMessage;
        }

        @Override
        public void run() {
            try {
                byte[] requestMessageByteData = null;
                if (requestMessage instanceof byte[]) {
                    requestMessageByteData = (byte[]) requestMessage;
                } else if (requestMessage instanceof String) {
                    requestMessageByteData = ((String) requestMessage).getBytes();
                }
                if (requestMessageByteData == null || requestMessageByteData.length == 0) {
                    System.out.println("客户端发送的数据为空");
                } else {

                    ByteBuffer data = ByteBuffer.allocate(requestMessageByteData.length + 4);
                    data.putInt(requestMessageByteData.length);
                    data.put(requestMessageByteData);
                    data.flip();
                    while (data.hasRemaining()) {
                        client.write(data);//写进channal
                    }
                    Date date = new Date();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS");
                    System.out.println("[" + sdf.format(date) + "][" + Thread.currentThread().getId() + "]客户端发送数据：["
                            + new String(requestMessageByteData) + "]");
                }
            } catch (Exception e) {
                System.out.println("客户端发送数据失败：[" + e.getMessage() + "]");
                close();
            } finally {
                isWriting.set(false);
                writeCount.decrementAndGet();
            }

        }
    }

    /**
     * 读数据线程
     *
     * @author haoguo
     *
     */
    private class ReceiveMessageHandler implements Runnable {
        private SocketChannel client;
        private ByteBuffer dataLen = ByteBuffer.allocate(4);

        private ReceiveMessageHandler(SocketChannel client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                dataLen.clear();//重置position
                int len = 4;
                while (len > 0) {
                    int readLen = client.read(dataLen);
                    if (readLen < 0) {
                        throw new Exception("readLen==" + readLen);
                    } else if (readLen == 0) {
                        System.out.println(Thread.currentThread().getId() + "readLen == 0");
                        return;
                    }
                    len -= readLen;
                }
                // dataLen.flip();
                // int data_length = dataLen.getInt();
                int data_length = dataLen.getInt(0);
                ByteBuffer data = ByteBuffer.allocate(data_length);
                while (data.hasRemaining()) {
                    client.read(data);//缓存读进
                }

                String receiveData = new String(data.array());
                Date date = new Date();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS");
                System.out.println("[" + sdf.format(date) + "][" + Thread.currentThread().getId() + "]客户端接收到服务器["
                        + client.getRemoteAddress() + "]数据 :[" + receiveData + "]");
                readCount.incrementAndGet();//以原子方式将当前值加 1。
            } catch (Exception e) {
                System.out.println("客户端接收数据失败：" + e);
                close();
            } finally {
                isReading.set(false);
            }
        }
    }

    public boolean isClose() {
        return isClose;
    }

    public void setClose(boolean close) {
        this.isClose = close;
    }

    public void close() {
        try {
            threadPool.shutdown();
            isClose = true;
            if (selector != null) {
                selector.close();
            }
            if (client != null) {
                client.close();
            }
        } catch (IOException e) {
            System.out.println("关闭客户端异常：" + e.getMessage());
        }
    }

    public void writeData(String data) {
        while (isWriting.get()) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        try {
            isWriting.set(true);
            writeCount.incrementAndGet();//原子方式+1
            client.register(selector, SelectionKey.OP_WRITE, data);
        } catch (Exception e) {
            System.out.println("客户端注册写通道异常：" + e.getMessage());
        }
    }

    public boolean hasWriteTask() {
        return writeCount.get() != 0;
    }

    public long getRecive() {
        return readCount.get();
    }

    public static void main(String[] args) {
        for (int j = 0; j < 20; j++) {
            new Thread(new Runnable() {

                @Override
                public void run() {

                    final JavaNIOClient client = new JavaNIOClient();
                    client.start();//SelectorGuardHandler() run
                    try {
                        TimeUnit.MILLISECONDS.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    long t1 = System.currentTimeMillis();
                    for (int i = 0; i < 500; i++) {
                        String ii = "00" + i;
                        ii = ii.substring(ii.length() - 3);
                        client.writeData(ii
                                + "nimddddddddddsssssssssssssssssssssssssssssssssssscccccccccccccccccccccccc"
                                + "ccccccccccccccccccccccccccccccccccccccccccccccccccccccccdddddddddddd"
                                + "dddddddddddddddddwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwaaaaaaaaaaaaaa"
                                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaddddddddddddddddddddddddddddddd"
                                + "ddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddrrrr"
                                + "jjjjjjjjjjjjjjjjjjjjjjjjjjjjrrrrrrrrrrrrrrrrrrrrrrrrrrrkkkkkkkkkkkkkkkkkkkk"
                                + "kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkjjjjkkkkkklllllllllllllllllllllllllll"
                                + "lllllllldddddddddddddmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmddaei"
                                + "nimddddddddddsssssssssssssssssssssssssssssssssssscccccccccccccccccccccccc"
                                + "ccccccccccccccccccccccccccccccccccccccccccccccccccccccccdddddddddddd"
                                + "dddddddddddddddddwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwaaaaaaaaaaaaaa"
                                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaddddddddddddddddddddddddddddddd"
                                + "ddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddrrrr"
                                + "jjjjjjjjjjjjjjjjjjjjjjjjjjjjrrrrrrrrrrrrrrrrrrrrrrrrrrrkkkkkkkkkkkkkkkkkkkk"
                                + "kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkjjjjkkkkkklllllllllllllllllllllllllll"
                                + "lllllllldddddddddddddmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmddaei" + i);
                    }

                    while (client.hasWriteTask()) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {

                            e.printStackTrace();
                        }
                    }
                    while (client.getRecive() != 500) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    long t2 = System.currentTimeMillis();
                    System.out.println("总共耗时：" + (t2 - t1) + "ms");
                    client.close();
                }

            }).start();
        }
    }
}

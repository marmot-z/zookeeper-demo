package com.zxw;

public class Main {
    static final ThreadLocal<String> tl1 = new ThreadLocal<>();
    static final ThreadLocal<String> tl2 = new ThreadLocal<>();

    static Thread t1 = new Thread(() -> {
        tl1.set("t1 threadLocal1 value");
        tl2.set("t1 threadLocal2 value");
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    });
    static Thread t2 = new Thread(() -> {
        tl1.set("t2 threadLocal1 value");
        tl2.set("t2 threadLocal2 value");
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    });

    public static void main1(String[] args) throws InterruptedException {
        t1.start();
        t2.start();

        Thread.sleep(1000);

        System.out.printf("end");
    }

    public static void main(String[] args) {
        ThreadLocal<String> tl1 = new ThreadLocal<>();
        ThreadLocal<String> tl2 = new ThreadLocal<>();
        tl1.set("t2 threadLocal1 value");
        tl2.set("t2 threadLocal2 value");
    }
}
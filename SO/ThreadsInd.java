package SO;

public class ThreadsInd {

    public static java.util.concurrent.Semaphore A = new java.util.concurrent.Semaphore(0);
    public static java.util.concurrent.Semaphore B = new java.util.concurrent.Semaphore(1);

    static class ThreadA extends Thread {
        public void run() {
            while(true) {
                double soma = 0;
                try {
                    A.acquire();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (int i = 0; i < 1000; i++) {
                    for (int j = 0; j < 2000; j++) {
                        soma = soma + Math.sin(i) + Math.cos(j);
                    }
                System.out.print("A");
                B.release();
                }
            }
        }
    }

    static class ThreadB extends Thread {
        public void run() {
            while(true) {
                double soma = 0;
                try {
                    B.acquire();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (int i = 0; i < 10000; i++) {
                    for (int j = 0; j < 2000; j++) {
                        soma = soma + Math.sin(i) + Math.cos(j);
                    }
                System.out.print("B");
                A.release();                 
                }
            }
        }
    }
    public static void main(String[] args) {
        ThreadA tA1 = new ThreadA();
        tA1.start();
        ThreadA tA2 = new ThreadA();
        tA2.start();
        ThreadB tB1 = new ThreadB();
        tB1.start();
    }
}

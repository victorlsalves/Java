package SO;

public class Main_threads {
    public static void main(String[] args) {
        teste_threads t1 = new teste_threads("Thread 1");
        teste_threads t2 = new teste_threads("Thread 2");
        
        t1.setPriority(Thread.MAX_PRIORITY);
        t2.setPriority(Thread.MIN_PRIORITY);
        
        t1.start();
        t2.start();
    
    }
}

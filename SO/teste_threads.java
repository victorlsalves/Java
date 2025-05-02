package SO;

public class teste_threads extends Thread {
    public teste_threads(String nome) {
        super(nome);
    }
    
    public void run() {
        long iterations = 0;
        while(true) 
        {
            double soma = 0.0;
            for (int i = 0; i < 10000; i++) 
            {
                for (int j = 0; j < 1000; j++) 
                {
                    soma = soma + Math.sin(i) + Math.sin(j);
                }
            }
            iterations++;
            System.out.println("Thread " + getName() + " iteracoes: " + iterations);
            try {
                Thread.sleep(1000); // Sleep for 1 second
            } catch (InterruptedException e) {
                System.out.println("Thread " + getName() + " interrupted.");
                break; // Exit the loop if interrupted
            }
        }
    }
}
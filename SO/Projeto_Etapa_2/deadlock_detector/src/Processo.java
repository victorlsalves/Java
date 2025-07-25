import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Processo extends Thread {
    private int id;
    private int deltaS;
    private int deltaU;
    private SistemaOperacional sistema;
    private List<RecursoInstance> recursosUsados = new ArrayList<>();
    private java.util.function.Consumer<String> logger;
    private ConcurrentMap<RecursoInstance, Thread> timers = new ConcurrentHashMap<>();
    private ConcurrentMap<RecursoInstance, Long> timerStartTimes = new ConcurrentHashMap<>();
    private Recurso recursoSolicitado = null;
    private int instanceCounter = 0;

    public Processo(int id, int deltaS, int deltaU, SistemaOperacional sistema, java.util.function.Consumer<String> logger) {
        this.id = id;
        this.deltaS = deltaS;
        this.deltaU = deltaU;
        this.sistema = sistema;
        this.logger = logger;
    }

    public int getProcessoId() {
        return id;
    }

    public String getProcessoName() {
        return "" + id;
    }

    public String status() {
        synchronized (recursosUsados) {
            StringBuilder status = new StringBuilder("Processo " + id);
            if (recursoSolicitado != null || sistema.getRecursoAguardado(this) != null) {
                Recurso aguardado = recursoSolicitado != null ? recursoSolicitado : sistema.getRecursoAguardado(this);
                status.append(" [bloqueado, aguardando ").append(aguardado.getNome());
                if (!recursosUsados.isEmpty()) {
                    status.append(", usando ");
                    status.append(String.join(", ", recursosUsados.stream()
                        .map(r -> r.getRecurso().getNome() + " (" + recursosUsados.stream().filter(x -> x.getRecurso() == r.getRecurso()).count() + ")")
                        .distinct()
                        .toList()));
                }
                status.append("]");
            } else if (recursosUsados.isEmpty()) {
                status.append(" [Bloqueado]");
            } else {
                status.append(" [rodando, usando ");
                status.append(String.join(", ", recursosUsados.stream()
                    .map(r -> r.getRecurso().getNome() + " (" + recursosUsados.stream().filter(x -> x.getRecurso() == r.getRecurso()).count() + ")")
                    .distinct()
                    .toList()));
                status.append("]");
            }
            return status.toString();
        }
    }

    public List<RecursoInstance> getRecursosUsados() {
        synchronized (recursosUsados) {
            return new ArrayList<>(recursosUsados);
        }
    }

    public Recurso getRecursoSolicitado() {
        return recursoSolicitado;
    }

    private void startTimerForRecurso(RecursoInstance instance) {
        timerStartTimes.put(instance, System.currentTimeMillis());
        Thread timer = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    long startTime = timerStartTimes.getOrDefault(instance, System.currentTimeMillis());
                    long elapsed = System.currentTimeMillis() - startTime;
                    long remaining = deltaU * 1000L - elapsed;
                    if (remaining <= 0) {
                        synchronized (recursosUsados) {
                            if (recursosUsados.remove(instance)) {
                                sistema.liberarRecurso(this, instance.getRecurso());
                            }
                            timers.remove(instance);
                            timerStartTimes.remove(instance);
                        }
                        synchronized (Processo.this) {
                            Processo.this.notify(); // Notify main loop
                        }
                        break;
                    }
                    synchronized (Processo.this) {
                        if (recursoSolicitado != null || sistema.getRecursoAguardado(this) != null) {
                            Processo.this.wait();
                        } else {
                            Thread.sleep(Math.min(remaining, 100));
                        }
                    }
                }
            } catch (InterruptedException e) {
                // Timer interrupted
            }
        });
        timers.put(instance, timer);
        timer.start();
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            try {
                synchronized (this) {
                    // Request a new resource if running, under limit (2-3 resources), and available instances exist
                    if (recursosUsados.size() < 3 && 
                        recursoSolicitado == null && 
                        sistema.getRecursoAguardado(this) == null &&
                        sistema.getRecursos().stream().anyMatch(r -> sistema.getAlocados(this).stream().filter(x -> x == r).count() < r.getTotal())) {
                        Thread.sleep(deltaS * 1000L);
                        logger.accept("Processo " + id + " solicitando recurso...");
                        recursoSolicitado = sistema.solicitarRecurso(this);
                        if (recursoSolicitado != null) {
                            // Resource acquired
                            synchronized (recursosUsados) {
                                RecursoInstance instance = new RecursoInstance(recursoSolicitado, instanceCounter++);
                                recursosUsados.add(instance);
                                startTimerForRecurso(instance);
                            }
                            recursoSolicitado = null;
                        } else if (sistema.getRecursoAguardado(this) == null) {
                            // No available instances, wait until notified
                            wait();
                        }
                    } else if (sistema.getRecursoAguardado(this) != null) {
                        // Block on the requested resource
                        recursoSolicitado = sistema.getRecursoAguardado(this);
                        logger.accept("Processo " + id + " bloqueado aguardando " + recursoSolicitado.getNome());
                        while (recursoSolicitado != null && !isInterrupted()) {
                            try {
                                wait();
                                recursoSolicitado = sistema.retryingSolicitarRecurso(this, recursoSolicitado);
                                if (recursoSolicitado == null) {
                                    // Resource acquired
                                    synchronized (recursosUsados) {
                                        List<Recurso> alocados = sistema.getAlocados(this);
                                        for (Recurso r : alocados) {
                                            if (!recursosUsados.stream().anyMatch(ri -> ri.getRecurso() == r)) {
                                                RecursoInstance instance = new RecursoInstance(r, instanceCounter++);
                                                recursosUsados.add(instance);
                                                startTimerForRecurso(instance);
                                            }
                                        }
                                    }
                                    recursoSolicitado = null;
                                    notify(); // Resume timers
                                }
                                recursoSolicitado = sistema.getRecursoAguardado(this);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    } else {
                        // No action possible (e.g., holding max instances), wait until notified
                        wait();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Cleanup
        timers.values().forEach(Thread::interrupt);
        synchronized (recursosUsados) {
            for (RecursoInstance ri : new ArrayList<>(recursosUsados)) {
                sistema.liberarRecurso(this, ri.getRecurso());
            }
            recursosUsados.clear();
        }
        timerStartTimes.clear();
        sistema.limparAguardando(this);
    }

    public void notifyProcess() {
        synchronized (this) {
            notify();
        }
    }
}
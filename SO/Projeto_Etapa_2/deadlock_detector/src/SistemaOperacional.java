
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class SistemaOperacional extends Thread {
    private List<Recurso> recursos = new ArrayList<>();
    private List<Processo> processos = new CopyOnWriteArrayList<>();
    private Map<Processo, List<Recurso>> alocados = new HashMap<>();
    private Map<Processo, Recurso> aguardando = new HashMap<>();
    private Map<Recurso, List<Processo>> processosAguardando = new HashMap<>();
    private java.util.function.Consumer<String> logger = System.out::println;
    private Runnable onUpdate = () -> {
    };
    private int intervaloVerificacao;
    private int[][] allocationMatrix;
    private int[][] requestMatrix;
    private int[] availableVector;

    public SistemaOperacional(int intervaloVerificacao) {
        this.intervaloVerificacao = intervaloVerificacao;
    }

    public void setLogger(java.util.function.Consumer<String> logFunc) {
        this.logger = logFunc;
    }

    public void setOnUpdate(Runnable r) {
        this.onUpdate = r;
    }

    public boolean adicionarRecurso(Recurso r) {
        if (recursos.size() >= 10)
            return false;
        for (Recurso recurso : recursos) {
            if (recurso.getId() == r.getId())
                return false;
        }
        recursos.add(r);
        processosAguardando.put(r, new ArrayList<>());
        updateMatrices();
        return true;
    }

    public void adicionarProcesso(Processo p) {
        if (processos.size() < 10) {
            processos.add(p);
            alocados.put(p, new ArrayList<>());
            updateMatrices();
        }
    }

    public void removerProcesso(Processo p) {
        processos.remove(p);
        List<Recurso> recursosAlocados = alocados.get(p);
        if (recursosAlocados != null) {
            for (Recurso r : new ArrayList<>(recursosAlocados)) {
                liberarRecurso(p, r); // Use liberarRecurso to ensure proper release and notifications
                logger.accept("Processo " + p.getProcessoName() + " removido, liberou recurso " + r.getNome());
            }
            alocados.remove(p);
        }
        Recurso r = aguardando.remove(p);
        if (r != null) {
            processosAguardando.get(r).remove(p);
        }
        updateMatrices();
        onUpdate.run();
    }

    public void limparAguardando(Processo p) {
        Recurso r = aguardando.remove(p);
        if (r != null) {
            processosAguardando.get(r).remove(p);
            updateMatrices();
            onUpdate.run();
        }
    }

    public Recurso getRecursoAguardado(Processo p) {
        return aguardando.get(p);
    }

    public List<Recurso> getRecursos() {
        return new ArrayList<>(recursos);
    }

    public List<Processo> getProcessos() {
        return processos;
    }

    public int getTotalRecursosSistema() {
        return recursos.stream().mapToInt(Recurso::getTotal).sum();
    }

    public Recurso solicitarRecurso(Processo p) {
        List<Recurso> recursosDisponiveis = recursos.stream()
                .filter(r -> {
                    long alocadosPorP = alocados.getOrDefault(p, new ArrayList<>())
                            .stream()
                            .filter(x -> x == r)
                            .count();
                    return r.getDisponivel() > 0 && alocadosPorP < r.getTotal();
                })
                .toList();

        if (recursosDisponiveis.isEmpty()) {
            logger.accept(
                    "Processo " + p.getProcessoName() + " não encontrou recurso elegível (monopoliza ou indisponível)");
            return null;
        }

        Recurso r = recursosDisponiveis.get(new Random().nextInt(recursosDisponiveis.size()));

        if (r.alocar()) {
            alocados.computeIfAbsent(p, k -> new ArrayList<>()).add(r);
            Recurso prev = aguardando.remove(p);
            if (prev != null) {
                processosAguardando.get(prev).remove(p);
            }
            logger.accept("Processo " + p.getProcessoName() + " obteve " + r.getNome());
            updateMatrices();
            onUpdate.run();
            return r;
        } else {
            // Se o recurso não pôde ser alocado, só bloqueia se o processo ainda não o
            // monopoliza
            long alocadosPorP = alocados.getOrDefault(p, new ArrayList<>())
                    .stream()
                    .filter(x -> x == r)
                    .count();
            if (alocadosPorP < r.getTotal()) {
                aguardando.put(p, r);
                processosAguardando.computeIfAbsent(r, k -> new ArrayList<>()).add(p);
                logger.accept("Processo " + p.getProcessoName() + " bloqueado aguardando " + r.getNome());
                updateMatrices();
                onUpdate.run();
            } else {
                logger.accept("Processo " + p.getProcessoName() + " já possui todas as instâncias de " + r.getNome()
                        + ". Ignorando.");
            }
            return null;
        }
    }

    public Recurso retryingSolicitarRecurso(Processo p, Recurso r) {
        if (r.alocar()) {
            alocados.computeIfAbsent(p, k -> new ArrayList<>()).add(r);
            aguardando.remove(p);
            processosAguardando.get(r).remove(p);
            logger.accept("Processo " + p.getProcessoName() + " obteve " + r.getNome());
            updateMatrices();
            onUpdate.run();
            return null;
        }
        return r;
    }

    public void liberarRecurso(Processo p, Recurso r) {
        List<Recurso> recursosAlocados = alocados.get(p);
        if (recursosAlocados != null) {
            if (recursosAlocados.remove(r)) {
                r.liberar();
                notifyWaitingProcesses(r);
                updateMatrices();
                onUpdate.run();
            }
        }
    }

    private void notifyWaitingProcesses(Recurso r) {
        List<Processo> waiting = new ArrayList<>(processosAguardando.getOrDefault(r, new ArrayList<>()));
        for (Processo p : waiting) {
            p.notifyProcess();
        }
    }

    public List<String> statusRecursos() {
        List<String> lista = new ArrayList<>();
        for (Recurso r : recursos) {
            lista.add(r.toString());
        }
        return lista;
    }

    public List<String> statusProcessos() {
        List<String> lista = new ArrayList<>();
        for (Processo p : processos) {
            lista.add(p.status());
        }
        return lista;
    }

    public String getAllocationMatrixString() {
        updateMatrices();
        StringBuilder sb = new StringBuilder();
        if (processos.isEmpty() || recursos.isEmpty()) {
            sb.append("Nenhuma alocação disponível.");
            return sb.toString();
        }
        sb.append(String.format("%-6s", ""));
        for (Recurso r : recursos) {
            sb.append(String.format("%-4s", r.getNome()));
        }
        sb.append("\n");
        for (int i = 0; i < processos.size(); i++) {
            sb.append(String.format("%-6s", "P" + processos.get(i).getProcessoName()));
            for (int j = 0; j < recursos.size(); j++) {
                sb.append(String.format("%-4d", allocationMatrix[i][j]));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public String getRequestMatrixString() {
        updateMatrices();
        StringBuilder sb = new StringBuilder();
        if (processos.isEmpty() || recursos.isEmpty()) {
            sb.append("Nenhuma requisição disponível.");
            return sb.toString();
        }
        sb.append(String.format("%-6s", ""));
        for (Recurso r : recursos) {
            sb.append(String.format("%-4s", r.getNome()));
        }
        sb.append("\n");
        for (int i = 0; i < processos.size(); i++) {
            sb.append(String.format("%-6s", "P" + processos.get(i).getProcessoName()));
            for (int j = 0; j < recursos.size(); j++) {
                sb.append(String.format("%-4d", requestMatrix[i][j]));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void updateMatrices() {
        int n = processos.size();
        int m = recursos.size();
        allocationMatrix = new int[n][m];
        requestMatrix = new int[n][m];
        availableVector = new int[m];

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                allocationMatrix[i][j] = 0;
                requestMatrix[i][j] = 0;
            }
        }
        for (int j = 0; j < m; j++) {
            availableVector[j] = recursos.get(j).getDisponivel();
        }

        for (int i = 0; i < n; i++) {
            Processo p = processos.get(i);
            List<Recurso> recursosAlocados = alocados.getOrDefault(p, new ArrayList<>());
            for (Recurso r : recursosAlocados) {
                int j = recursos.indexOf(r);
                if (j >= 0)
                    allocationMatrix[i][j]++;
            }
            Recurso r = aguardando.get(p);
            if (r != null) {
                int j = recursos.indexOf(r);
                if (j >= 0)
                    requestMatrix[i][j] = 1;
            }
        }
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            try {
                Thread.sleep(intervaloVerificacao * 1000L);
                detectarDeadlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void detectarDeadlock() {
        if (recursos.isEmpty() || processos.size() <= 1)
            return;

        updateMatrices();
        int n = processos.size();
        int m = recursos.size();
        int[] work = availableVector.clone();
        boolean[] finish = new boolean[n];
        Arrays.fill(finish, false);

        List<Integer> safeSequence = new ArrayList<>();
        boolean progress;
        do {
            progress = false;
            for (int i = 0; i < n; i++) {
                if (!finish[i]) {
                    boolean canRun = true;
                    for (int j = 0; j < m; j++) {
                        if (requestMatrix[i][j] > work[j]) {
                            canRun = false;
                            break;
                        }
                    }
                    if (canRun) {
                        for (int j = 0; j < m; j++) {
                            work[j] += allocationMatrix[i][j];
                        }
                        finish[i] = true;
                        safeSequence.add(i);
                        progress = true;
                    }
                }
            }
        } while (progress && safeSequence.size() < n);

        if (safeSequence.size() < n) {
            List<String> deadlocked = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (!finish[i]) {
                    deadlocked.add(processos.get(i).getProcessoName());
                }
            }
            if (deadlocked.size() > 1) {
                logger.accept("⚠ DEADLOCK DETECTADO entre processos: " + deadlocked);
            }
        } else {
            logger.accept("Sistema está em estado seguro.");
        }
        onUpdate.run();
    }
}

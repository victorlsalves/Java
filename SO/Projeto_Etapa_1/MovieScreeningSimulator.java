import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.sql.Time;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.text.DefaultCaret;

public class MovieScreeningSimulator {

    // Configuration
    private int N_CAPACITY;
    private int TE_MOVIE_DURATION_SECONDS;

    // Semaphores
    private Semaphore semSeats;
    private Semaphore semAuditoriumMutex;
    private Semaphore semDemonstratorWakeUp;
    private Semaphore semMovieStarted;
    private Semaphore semMovieFinished;
    private Semaphore semAllFansLeft;

    // Shared state
    private volatile int currentFanCountInAuditorium = 0;
    private volatile int fansLeftThisSession = 0;
    private AtomicInteger fanIdCounter = new AtomicInteger(1);
    private final List<Fan> fanThreads = Collections.synchronizedList(new ArrayList<>());
    private static boolean movieIsOn = false;

    // Image resources
    private Map<String, BufferedImage> characterImages;
    private String[] characterColors = {
        "cor1", "cor2", "cor3", "cor4", "cor5",
        "cor6", "cor7", "cor8", "cor9", "cor10"
    };
    private BufferedImage backgroundImage;

    // GUI Components
    private JFrame frame;
    private JTextField capacityField;
    private JTextField movieTimeField;
    private JTextField lunchTimeField;
    private JButton startSimulationButton;
    private JButton addFanButton;
    private JButton removeFanButton;
    private JLabel demonstratorStatusLabel;
    private VisualizacaoPanel visualizacaoPanel;
    private JTextArea logArea;

    private volatile boolean simulationRunning = false;
    private Demonstrator demonstratorThread;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MovieScreeningSimulator::new);
    }

    public MovieScreeningSimulator() {
        loadImages();
        createAndShowGUI();
    }

    private void loadImages() {
        characterImages = new HashMap<>();
        for (String color : characterColors) {
            try {
                String imagePath = "images/" + color + ".png";
                File imageFile = new File(imagePath);
                if (!imageFile.exists()) {
                    System.err.println("ARQUIVO DE PERSONAGEM NÃO ENCONTRADO: " + imageFile.getAbsolutePath());
                    characterImages.put(color, null);
                    continue;
                }
                BufferedImage img = ImageIO.read(imageFile);
                if (img == null) {
                    System.err.println("Não foi possível decodificar a imagem do personagem: " + imagePath);
                }
                characterImages.put(color, img);
            } catch (IOException e) {
                System.err.println("Erro de I/O ao carregar imagem " + color + ".png: " + e.getMessage());
                characterImages.put(color, null);
            }
        }
        long failedCharacterLoads = characterImages.values().stream().filter(java.util.Objects::isNull).count();
        if (failedCharacterLoads > 0 || characterImages.size() < characterColors.length) {
            String missingFilesMessage = "Verifique a pasta 'images' e os nomes dos arquivos (ex: cor1.png ... cor10.png).";
            JOptionPane.showMessageDialog(null,
                failedCharacterLoads + " imagem(ns) de personagem(ns) não puderam ser carregadas!\n" +
                missingFilesMessage + "\n" +
                "Fãs podem ser invisíveis ou usar fallback.",
                "Erro ao Carregar Imagens de Personagem", JOptionPane.WARNING_MESSAGE);
        } else {
             System.out.println("Imagens de personagem carregadas: " + characterImages.keySet());
        }

        try {
            String bgPath = "images/background.png";
            File bgFile = new File(bgPath);
            if (!bgFile.exists()) {
                System.err.println("ARQUIVO DE FUNDO NÃO ENCONTRADO: " + bgFile.getAbsolutePath());
                backgroundImage = null;
            } else {
                backgroundImage = ImageIO.read(bgFile);
                if (backgroundImage == null) {
                    System.err.println("Não foi possível decodificar a imagem de fundo: " + bgPath);
                } else {
                    System.out.println("Imagem de fundo carregada com sucesso.");
                }
            }
        } catch (IOException e) {
            System.err.println("Erro de I/O ao carregar imagem de fundo: " + e.getMessage());
            backgroundImage = null;
        }
        if (backgroundImage == null && (characterImages.isEmpty() || characterImages.values().stream().anyMatch(java.util.Objects::isNull))) {
             JOptionPane.showMessageDialog(null,
                "Algumas imagens (personagens ou fundo) não puderam ser carregadas da pasta 'images'.\n" +
                "Verifique o console para mais detalhes.\n" +
                "A simulação pode não ter o visual esperado.",
                "Erro ao Carregar Imagens", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void createAndShowGUI() {
        frame = new JFrame("Simulador de Exibição de Filme (Pelé)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        JPanel inputPanel = new JPanel(new GridBagLayout());
        inputPanel.setBorder(BorderFactory.createTitledBorder("Configurações"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridx = 0; gbc.gridy = 0; inputPanel.add(new JLabel("Capacidade Auditório (N):"), gbc);
        capacityField = new JTextField("5", 5);
        gbc.gridx = 1; gbc.gridy = 0; inputPanel.add(capacityField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; inputPanel.add(new JLabel("Tempo Filme (Te segs):"), gbc);
        movieTimeField = new JTextField("10", 5);
        gbc.gridx = 1; gbc.gridy = 1; inputPanel.add(movieTimeField, gbc);

        startSimulationButton = new JButton("Iniciar Simulação");
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; inputPanel.add(startSimulationButton, gbc);

        gbc.gridy = 3; inputPanel.add(new JSeparator(), gbc);

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 1; inputPanel.add(new JLabel("Tempo Lanche Fã (Tl segs):"), gbc);
        lunchTimeField = new JTextField("8", 5);
        gbc.gridx = 1; gbc.gridy = 4; inputPanel.add(lunchTimeField, gbc);

        addFanButton = new JButton("Adicionar Fã");
        addFanButton.setEnabled(false);
        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2; inputPanel.add(addFanButton, gbc);

        removeFanButton = new JButton("Remover Fã");
        removeFanButton.setEnabled(false);
        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 2; inputPanel.add(removeFanButton, gbc);

        logArea = new JTextArea(10, 25);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        DefaultCaret caret = (DefaultCaret)logArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logScrollPane.setBorder(BorderFactory.createTitledBorder("Log de Eventos"));
        logScrollPane.setPreferredSize(new Dimension(280, 200));
        inputPanel.setPreferredSize(new Dimension(280, 220));


        JSplitPane leftSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, inputPanel, logScrollPane);
        leftSplitPane.setDividerLocation(230);
        leftSplitPane.setResizeWeight(0.4);


        JPanel statusPanelRight = new JPanel(new BorderLayout(5,5));
        demonstratorStatusLabel = new JLabel("Demonstrador: Ocioso", SwingConstants.CENTER);
        demonstratorStatusLabel.setBorder(BorderFactory.createEtchedBorder());
        statusPanelRight.add(demonstratorStatusLabel, BorderLayout.NORTH);

        visualizacaoPanel = new VisualizacaoPanel(backgroundImage);
        statusPanelRight.add(visualizacaoPanel, BorderLayout.CENTER);

        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplitPane, statusPanelRight);
        mainSplitPane.setDividerLocation(300);
        mainSplitPane.setResizeWeight(0.3);

        frame.add(mainSplitPane, BorderLayout.CENTER);

        startSimulationButton.addActionListener(e -> startSimulation());
        addFanButton.addActionListener(e -> createFan());
        removeFanButton.addActionListener(e -> {
            String input = JOptionPane.showInputDialog(frame, "Digite o número do fã para remover (ex: 1 para Fã-1):", "Remover Fã", JOptionPane.QUESTION_MESSAGE);
            if (input != null) {
                try {
                    int fanNumber = Integer.parseInt(input.trim());
                    if (fanNumber <= 0) throw new NumberFormatException();
                    removeFanByNumber(fanNumber);
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(frame, "Número inválido.", "Erro", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            if (logArea != null) {
                logArea.append(message + "\n");
            }
        });
        System.out.println(message);
    }

    private void updateDemonstratorStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            if (demonstratorStatusLabel != null) {
                 demonstratorStatusLabel.setText("Demonstrador: " + status);
            }
             if (visualizacaoPanel != null) {
                visualizacaoPanel.repaint();
            }
        });
    }

    private void startSimulation() {
        if (simulationRunning) {
            log("Simulação já está em execução.");
            return;
        }
        try {
            N_CAPACITY = Integer.parseInt(capacityField.getText().trim());
            TE_MOVIE_DURATION_SECONDS = Integer.parseInt(movieTimeField.getText().trim());
            if (N_CAPACITY <= 0 || TE_MOVIE_DURATION_SECONDS <= 0) {
                JOptionPane.showMessageDialog(frame, "N e Te devem ser positivos.", "Erro", JOptionPane.ERROR_MESSAGE);
                return;
            }
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(frame, "Valores inválidos para N ou Te.", "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }

        semSeats = new Semaphore(N_CAPACITY, true);
        semAuditoriumMutex = new Semaphore(1, true);
        semDemonstratorWakeUp = new Semaphore(0, true);
        semMovieStarted = new Semaphore(0, true);
        //semMovieFinished = new Semaphore(0, true);
        semAllFansLeft = new Semaphore(0, true);

        currentFanCountInAuditorium = 0;
        fansLeftThisSession = 0;
        fanIdCounter.set(1);

        synchronized(fanThreads) {
            fanThreads.clear();
        }
        if(visualizacaoPanel != null) visualizacaoPanel.clearFans();
        if(logArea != null) logArea.setText("");

        simulationRunning = true;
        startSimulationButton.setEnabled(false);
        addFanButton.setEnabled(true);
        removeFanButton.setEnabled(true);
        capacityField.setEnabled(false);
        movieTimeField.setEnabled(false);

        log("==== SIMULAÇÃO INICIADA ====");
        log("Capacidade do Auditório (N): " + N_CAPACITY);
        log("Tempo do Filme (Te): " + TE_MOVIE_DURATION_SECONDS + "s");

        demonstratorThread = new Demonstrator();
        demonstratorThread.start();
        updateDemonstratorStatus("Aguardando Lotação (0/" + N_CAPACITY + ")");
    }

    private void createFan() {
        if (!simulationRunning) {
            JOptionPane.showMessageDialog(frame, "Inicie a simulação primeiro.", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int tlFanLunchTime;
        try {
            tlFanLunchTime = Integer.parseInt(lunchTimeField.getText().trim());
            if (tlFanLunchTime <= 0) {
                JOptionPane.showMessageDialog(frame, "Tempo de lanche (Tl) deve ser positivo.", "Erro", JOptionPane.ERROR_MESSAGE);
                return;
            }
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(frame, "Valor inválido para Tl.", "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String fanId = "Fã-" + fanIdCounter.get();
        String fanColorName = characterColors[(fanIdCounter.get() - 1) % characterColors.length];
        BufferedImage fanImage = characterImages.get(fanColorName);

        Fan fan = new Fan(fanId, tlFanLunchTime, fanImage);
        fanIdCounter.getAndIncrement();

        fanThreads.add(fan);
        if (visualizacaoPanel != null) visualizacaoPanel.addFanSprite(fan);
        fan.start();
        log(fanId + " (imagem: "+ fanColorName +".png) criado (Tl=" + tlFanLunchTime + "s).");
    }

    private void removeFanByNumber(int fanNumber) {
        String fanId = "Fã-" + fanNumber;
        Fan fanToRemove = null;
        synchronized (fanThreads) {
            for (Fan fan : fanThreads) {
                if (fan.getFanId().equals(fanId)) {
                    fanToRemove = fan;
                    break;
                }
            }
        }
        if (fanToRemove != null) {
            String status = fanToRemove.getVisualStatus();
            if ("Na fila".equals(status) || "Lanchando".equals(status)) {
                log("Removendo " + fanId + "...");
                fanToRemove.interrupt();
                if (!fanToRemove.acquiredSeat) {
                    semSeats.release();
                    log(fanId + ": Assento liberado ao remover fã antes de entrar no auditório.");
                }
                // O próprio fã remove-se da lista e da visualização no finally do run()
            } else if ("Aguardando filme".equals(status)) {
                log("Removendo " + fanId + " (já no auditório, aguardando filme)...");
                fanToRemove.interrupt();
                try {
                    semAuditoriumMutex.acquire();
                    if (currentFanCountInAuditorium > 0) {
                        currentFanCountInAuditorium--;
                        fansLeftThisSession++;
                        log(fanId + ": Removido do auditório. Total agora: " + currentFanCountInAuditorium + "/" + N_CAPACITY);
                        if (visualizacaoPanel != null) SwingUtilities.invokeLater(visualizacaoPanel::repaint);
                        if (fansLeftThisSession == N_CAPACITY) {
                            log(fanId + ": Era o último a sair desta sessão (" + N_CAPACITY + "/" + N_CAPACITY + "). Avisando o demonstrador que esvaziou.");
                            semAllFansLeft.release();
                            fansLeftThisSession = 0;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    semAuditoriumMutex.release();
                }
                semSeats.release();
                log(fanId + ": Assento liberado ao remover fã que já estava no auditório.");
            } else {
                JOptionPane.showMessageDialog(frame,
                    "O fã só pode ser removido enquanto está na fila, lanchando ou aguardando o filme.\nStatus atual: " + status,
                    "Remoção não permitida",
                    JOptionPane.WARNING_MESSAGE);
            }
        } else {
            JOptionPane.showMessageDialog(frame, "Fã não encontrado: " + fanId, "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    class Demonstrator extends Thread {
        @Override
        public void run() {
            log("DEMONSTRADOR: Thread iniciada.");
            while (simulationRunning) {
                try {
                    log("DEMONSTRADOR: Aguardando auditório encher (" + currentFanCountInAuditorium + "/" + N_CAPACITY +")...");
                    semDemonstratorWakeUp.acquire();

                    String showingStatus = "Exibindo Filme (" + currentFanCountInAuditorium + "/" + N_CAPACITY + ")";
                    updateDemonstratorStatus(showingStatus);
                    log("DEMONSTRADOR: Auditório lotado ("+ currentFanCountInAuditorium + "/" + N_CAPACITY + "). Iniciando filme...");

                    log("DEMONSTRADOR: Exibindo filme por " + TE_MOVIE_DURATION_SECONDS + "s...");
                    movieIsOn = true;
                    long tempoInicio = System.currentTimeMillis();
                    int contadorLog = 0;

                    semMovieStarted.release(N_CAPACITY);
                    while ((System.currentTimeMillis() - tempoInicio) < TE_MOVIE_DURATION_SECONDS * 1000L) {
                        if (contadorLog++ % 100000000 == 0) { //Exibe log a cada 100.000.000 iterações
                            long segundosDecorridos = (System.currentTimeMillis() - tempoInicio) / 1000;
                            long segundosRestantes = TE_MOVIE_DURATION_SECONDS - segundosDecorridos;
                            log("DEMONSTRADOR: Filme em progresso (" + segundosRestantes + "s restantes)");
                        }
                    }

                    movieIsOn = false;
                    log("DEMONSTRADOR: Filme encerrado.");
                    //semMovieFinished.release(N_CAPACITY);
                    updateDemonstratorStatus("Aguardando Esvaziar");

                    log("DEMONSTRADOR: Aguardando todos os " + N_CAPACITY + " fãs saírem...");
                    semAllFansLeft.acquire();
                    log("DEMONSTRADOR: Auditório vazio. Preparando para próxima sessão.");
                    updateDemonstratorStatus("Aguardando Lotação (0/" + N_CAPACITY + ")");
                } catch (InterruptedException e) {
                    log("DEMONSTRADOR: Thread interrompida.");
                    simulationRunning = false;
                    break;
                }
            }
            updateDemonstratorStatus("Ocioso (Simulação Encerrada)");
            log("DEMONSTRADOR: Thread finalizada.");
            if (startSimulationButton != null) startSimulationButton.setEnabled(true);
            if (addFanButton != null) addFanButton.setEnabled(false);
            if (capacityField != null) capacityField.setEnabled(true);
            if (movieTimeField != null) movieTimeField.setEnabled(true);
        }
    }

    class Fan extends Thread {
        private final String fanId;
        private final int tlLunchTimeSeconds;
        public BufferedImage image;
        public int x, y;
        public int targetX, targetY;
        private String currentStatusForVisuals;
        private boolean moving;
        private boolean visible = true;
        private volatile boolean acquiredSeat = false; // NOVO CAMPO

        public Fan(String fanId, int tlLunchTime, BufferedImage image) {
            this.fanId = fanId;
            this.tlLunchTimeSeconds = tlLunchTime;
            this.image = image;
            this.currentStatusForVisuals = "Na fila";

            this.x = VisualizacaoPanel.PONTO_ENTRADA_X;
            this.y = VisualizacaoPanel.PONTO_ENTRADA_Y;

            this.targetX = this.x;
            this.targetY = this.y;
            this.moving = false;
        }

        private void setVisualStatus(String status) {
            this.currentStatusForVisuals = status;
            if (visualizacaoPanel != null) {
                visualizacaoPanel.setFanTargetPosition(this);
                visualizacaoPanel.repaint(); 
            }
        }

        public String getVisualStatus() { return currentStatusForVisuals; }
        public String getFanId() { return fanId; }
        public boolean isMoving() { return moving; }
        public void setMoving(boolean moving) { this.moving = moving; }

        @Override
        public void run() {
            log(fanId + ": Thread iniciada (Lanche=" + tlLunchTimeSeconds + "s). Imagem: " + (image != null ? "Carregada" : "NÃO CARREGADA"));
            try {
                while (simulationRunning) {
                    setVisualStatus("Na fila");
                    log(fanId + ": Na fila para entrar.");
                    semSeats.acquire();
                    acquiredSeat = true; // MARCA QUE O FÃ PEGOU O ASSENTO
                    log(fanId + ": Conseguiu permissão de 'assento geral'. Tentando entrar no auditório.");

                    semAuditoriumMutex.acquire();
                    currentFanCountInAuditorium++;
                    log(fanId + ": Entrou no auditório. Total: " + currentFanCountInAuditorium + "/" + N_CAPACITY);
                    if (visualizacaoPanel != null) SwingUtilities.invokeLater(visualizacaoPanel::repaint);
                    setVisualStatus("Aguardando filme");


                    if (currentFanCountInAuditorium == N_CAPACITY) {
                        log(fanId + ": Eu sou o " + N_CAPACITY + "º fã! Avisando o demonstrador.");
                        semDemonstratorWakeUp.release();
                    }
                    semAuditoriumMutex.release();

                    log(fanId + ": Esperando o filme começar...");
                    semMovieStarted.acquire();
                    int contadorLog = 0;
                    setVisualStatus("Assistindo filme");
                    while(movieIsOn == true){
                        if (contadorLog++ % 100000000 == 0) { //Exibe log a cada 100.000.000 iterações

                            log(fanId + ": Filme começou! Assistindo...");
                        }
                    }
                    //semMovieFinished.acquire();

                    semAuditoriumMutex.acquire();
                    currentFanCountInAuditorium--;
                    fansLeftThisSession++;
                    if (visualizacaoPanel != null) SwingUtilities.invokeLater(visualizacaoPanel::repaint);
                    setVisualStatus("Saindo para lanchar");
                    log(fanId + ": Filme acabou. Saiu do auditório. (" + fansLeftThisSession + "/" + N_CAPACITY + " saíram desta sessão)");
                    if (fansLeftThisSession == N_CAPACITY) {
                        log(fanId + ": Eu sou o último a sair desta sessão (" + N_CAPACITY + "/" + N_CAPACITY + "). Avisando o demonstrador que esvaziou.");
                        semAllFansLeft.release();
                        fansLeftThisSession = 0;
                    }
                    semAuditoriumMutex.release();

                    semSeats.release();
                    log(fanId + ": Terminou de assistir. Devolvendo 'assento geral' e indo lanchar.");

                    setVisualStatus("Lanchando");  
                    long tempoInicio = System.currentTimeMillis();     
                    contadorLog = 0;               
                    while ((System.currentTimeMillis() - tempoInicio) < tlLunchTimeSeconds * 1000L) {
                        if (contadorLog++ % 100000000 == 0) { //Exibe log a cada 100.000.000 iterações
                                long segundosDecorridos = (System.currentTimeMillis() - tempoInicio) / 1000;
                                long segundosRestantes = tlLunchTimeSeconds - segundosDecorridos;
                                log(fanId + ": Lanchando... (" + segundosRestantes + "s restantes)");
                        }
                    }
                }
            } catch (InterruptedException e) {
                log(fanId + ": Thread interrompida.");
                Thread.currentThread().interrupt();
            } finally {
                setVisualStatus("Encerrado");
                log(fanId + ": Thread finalizada.");
                final Fan fanToRemove = this;
                if (visualizacaoPanel != null) {
                     SwingUtilities.invokeLater(() -> visualizacaoPanel.removeFanSprite(fanToRemove));
                }
                synchronized(fanThreads) {
                    fanThreads.remove(this);
                }
            }
        }
    }

    class VisualizacaoPanel extends JPanel implements ActionListener {
        private final List<Fan> fansToDraw = Collections.synchronizedList(new ArrayList<>());

        // Coordenadas das Áreas
        public static final int AREA_AUDITORIO_X = 70;
        public static final int AREA_LANCHONETE_X = 500; // Ajuste para o início da área da lanchonete
        public static final int AREA_FILA_X = 275;
        public static final int AREA_Y_START_TOP = 70;
        private static final int AREA_Y_BOTTOM = 320;

        // Adicione estas linhas:
        private static final int LANCHE_Y_TOP = 70;    // topo da área da lanchonete
        private static final int LANCHE_Y_BOTTOM = 320; // base da área da lanchonete

        // Constantes de Imagem e Espaçamento ANTES de PONTO_ENTRADA_Y
        private static final int IMAGE_TARGET_WIDTH = 45;
        private static final int IMAGE_TARGET_HEIGHT = 60;
        private static final int Y_SPACING = 60;

        // Ponto de entrada fixo para novos fãs
        public static final int PONTO_ENTRADA_X = AREA_FILA_X;
        public static final int PONTO_ENTRADA_Y = AREA_Y_BOTTOM - IMAGE_TARGET_HEIGHT - (Y_SPACING / 2) - 10; // Ajustado para ficar um pouco mais acima da base da fila

        // Timer e outras variáveis
        private Timer animationTimer;
        private static final int MOVEMENT_SPEED = 10;
        private static final int ANIMATION_DELAY = 40;
        private BufferedImage panelBackgroundImage;

        public VisualizacaoPanel(BufferedImage backgroundImage) {
            this.panelBackgroundImage = backgroundImage;
            this.setPreferredSize(new Dimension(600, 450));
            if (this.panelBackgroundImage == null) {
                this.setBackground(new Color(20, 20, 20));
            }
            animationTimer = new Timer(ANIMATION_DELAY, this);
            animationTimer.start();
        }

        public void addFanSprite(Fan fan) {
            synchronized(fansToDraw) {
                fansToDraw.add(fan);
                assignInitialFanPosition(fan);
                fan.setMoving(true);
            }
            repaint();
        }


        private void assignInitialFanPosition(Fan fan) {
            // Apenas coloque o fã no ponto de entrada
            fan.targetX = PONTO_ENTRADA_X;
            fan.targetY = PONTO_ENTRADA_Y;
            fan.x = fan.targetX;
            fan.y = fan.targetY;
            fan.setMoving(false);
        }


        public void removeFanSprite(Fan fan) {
            synchronized(fansToDraw) {
                fansToDraw.remove(fan);
            }
            repaint();
        }

        public void clearFans() {
            synchronized(fansToDraw) {
                fansToDraw.clear();
            }
            repaint();
        }

        public void setFanTargetPosition(Fan fan) {
            assignFanPositionBasedOnStatus(fan); // Corrigido o nome do método
            if (fan.x != fan.targetX || fan.y != fan.targetY) {
                fan.setMoving(true);
            }
        }

        private int countFansInArea(List<Fan> fans, String areaStatus) {
            int count = 0;
            for (Fan fan : fans) {
                if (areaStatus.equals(fan.getVisualStatus())) {
                    count++;
                }
            }
            return count;
        }


        private void assignFanPositionBasedOnStatus(Fan fan) {
            String targetStatus = fan.getVisualStatus();
            if (targetStatus == null) return;

            switch (targetStatus) {
                case "Na fila":
                    int maxFila = 9;
                    int panelHeight = this.getHeight() > 0 ? this.getHeight() : 450;
                    int filaTop = AREA_Y_START_TOP;
                    int filaBottom = panelHeight - IMAGE_TARGET_HEIGHT - 10;
                    int availableHeight = filaBottom - filaTop;
                    int dynamicSpacing = Y_SPACING;
                    if (maxFila > 1) {
                        dynamicSpacing = Math.max(1, availableHeight / (maxFila - 1));
                    }

                    // Lista dos fãs na fila, na ordem de chegada
                    List<Fan> filaFans;
                    synchronized(fansToDraw) {
                        filaFans = new ArrayList<>();
                        for (Fan f : fansToDraw) {
                            if ("Na fila".equals(f.getVisualStatus())) {
                                filaFans.add(f);
                            }
                        }
                    }
                    int filaIndex = filaFans.indexOf(fan);
                    if (filaIndex >= 0 && filaIndex < maxFila) {
                        fan.targetX = AREA_FILA_X;
                        fan.targetY = filaTop + (filaIndex * dynamicSpacing);
                        fan.visible = true;
                    } else {
                        // Se for o 10º ou mais, não desenhe na fila
                        fan.visible = false;
                    }
                    break;

                case "Aguardando filme":
                    fan.visible = true;
                    break;
                case "Assistindo filme":
                    fan.visible = false;
                    fan.targetX = AREA_AUDITORIO_X;
                    fan.targetY = AREA_Y_START_TOP;
                    break;

                case "Saindo para lanchar":
                case "Lanchando":
                    fan.visible = true;
                    int maxLanche = 9;
                    int lancheTop = LANCHE_Y_TOP;
                    int lancheBottom = LANCHE_Y_BOTTOM;
                    int lancheAvailableHeight = lancheBottom - lancheTop;
                    int lancheSpacing = Y_SPACING;
                    if (maxLanche > 1) {
                        lancheSpacing = Math.max(1, lancheAvailableHeight / (maxLanche - 1));
                    }
                    // Lista dos fãs lanchando, na ordem de chegada
                    List<Fan> lancheFans;
                    synchronized(fansToDraw) {
                        lancheFans = new ArrayList<>();
                        for (Fan f : fansToDraw) {
                            if ("Lanchando".equals(f.getVisualStatus()) || "Saindo para lanchar".equals(f.getVisualStatus())) {
                                lancheFans.add(f);
                            }
                        }
                    }
                    int lancheIndex = lancheFans.indexOf(fan);
                    if (lancheIndex >= 0 && lancheIndex < maxLanche) {
                        fan.targetX = AREA_LANCHONETE_X;
                        fan.targetY = lancheTop + (lancheIndex * lancheSpacing);
                        fan.visible = true;
                    } else {
                        fan.visible = false;
                    }
                    break;

                default:
                    fan.visible = false;
                    fan.targetX = - (IMAGE_TARGET_WIDTH + 50);
                    fan.targetY = - (IMAGE_TARGET_HEIGHT + 50);
                    break;
            }
        }


        @Override
        public void actionPerformed(ActionEvent e) {
            boolean needsRepaint = false;
            List<Fan> currentFansToAnimate;
            synchronized(fansToDraw) {
                currentFansToAnimate = new ArrayList<>(fansToDraw);
            }

            for (Fan fan : currentFansToAnimate) {
                 if (fan.isMoving() && (fan.x != fan.targetX || fan.y != fan.targetY)) {
                    needsRepaint = true;
                    if (fan.x < fan.targetX) fan.x = Math.min(fan.x + MOVEMENT_SPEED, fan.targetX);
                    else if (fan.x > fan.targetX) fan.x = Math.max(fan.x - MOVEMENT_SPEED, fan.targetX);
                    if (fan.y < fan.targetY) fan.y = Math.min(fan.y + MOVEMENT_SPEED, fan.targetY);
                    else if (fan.y > fan.targetY) fan.y = Math.max(fan.y - MOVEMENT_SPEED, fan.targetY);

                    if (fan.x == fan.targetX && fan.y == fan.targetY) {
                        fan.setMoving(false);
                    }
                } else if (fan.isMoving()) {
                     fan.setMoving(false);
                }
            }
            if (needsRepaint) {
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;

            if (panelBackgroundImage != null) {
                g2d.drawImage(panelBackgroundImage, 0, 0, getWidth(), getHeight(), this);
            } else {
                g2d.setColor(new Color(20,20,20));
                g2d.fillRect(0,0,getWidth(),getHeight());
            }

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            // (As áreas coloridas de fundo foram removidas na versão anterior, conforme pedido)

            if (MovieScreeningSimulator.this.N_CAPACITY > 0) {
                g2d.setColor(Color.ORANGE);
                g2d.setFont(new Font("SansSerif", Font.BOLD, 18));
                String auditoriumStatusText = "Auditório: " + MovieScreeningSimulator.this.currentFanCountInAuditorium + "/" + MovieScreeningSimulator.this.N_CAPACITY;
                FontMetrics fmStatus = g2d.getFontMetrics();
                // int statusTextWidth = fmStatus.stringWidth(auditoriumStatusText); // Não usado se não centralizar horizontalmente
                g2d.drawString(auditoriumStatusText, AREA_AUDITORIO_X, AREA_Y_START_TOP - fmStatus.getDescent() - 10);
            }

            // Desenhe a área da lanchonete para referência visual
            g2d.setColor(new Color(255, 200, 100, 60));
            g2d.fillRect(AREA_LANCHONETE_X, LANCHE_Y_TOP, IMAGE_TARGET_WIDTH, LANCHE_Y_BOTTOM - LANCHE_Y_TOP);

            List<Fan> fansCopy;
            synchronized(fansToDraw) {
                fansCopy = new ArrayList<>(this.fansToDraw);
            }

            for (Fan fan : fansCopy) {
                if (fan != null && fan.visible &&  fan.image != null) {
                    g2d.drawImage(fan.image, fan.x, fan.y, IMAGE_TARGET_WIDTH, IMAGE_TARGET_HEIGHT, this);
                } else if (fan != null && fan.image == null) {
                    g2d.setColor(Color.DARK_GRAY);
                    g2d.fillRect(fan.x, fan.y, IMAGE_TARGET_WIDTH, IMAGE_TARGET_HEIGHT);
                }
            }
        }
    }

}
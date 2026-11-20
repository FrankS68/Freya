package de.sourcescout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "scout")
public class ScoutProperties {

    private final Schedule schedule = new Schedule();
    private final Search search = new Search();
    private final Mistral mistral = new Mistral();
    private final Gemini gemini = new Gemini();
    private final OpenAi openai = new OpenAi();
    private final Evaluation evaluation = new Evaluation();
    private final Portal portal = new Portal();
    private final Store store = new Store();
    private final Update update = new Update();
    private final LogReport logReport = new LogReport();

    public Schedule getSchedule() {
        return schedule;
    }

    public Search getSearch() {
        return search;
    }

    public Mistral getMistral() {
        return mistral;
    }

    public Gemini getGemini() {
        return gemini;
    }

    public OpenAi getOpenai() {
        return openai;
    }

    public LogReport getLogReport() {
        return logReport;
    }

    public Evaluation getEvaluation() {
        return evaluation;
    }

    public Portal getPortal() {
        return portal;
    }

    public Store getStore() {
        return store;
    }

    public Update getUpdate() {
        return update;
    }

    public static class Schedule {
        private String cron = "0 0 */6 * * *";
        private boolean runOnStartup = true;

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public boolean isRunOnStartup() {
            return runOnStartup;
        }

        public void setRunOnStartup(boolean runOnStartup) {
            this.runOnStartup = runOnStartup;
        }
    }

    public static class Search {
        private List<String> topics = List.of("Beispielthema");
        private List<String> providers = List.of("mistral", "gemini");
        private String strategy = "rotate";
        private int resultsPerProvider = 8;
        private String language = "de";

        public List<String> getTopics() {
            return topics;
        }

        public void setTopics(List<String> topics) {
            this.topics = topics;
        }

        public List<String> getProviders() {
            return providers;
        }

        public void setProviders(List<String> providers) {
            this.providers = providers;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public int getResultsPerProvider() {
            return resultsPerProvider;
        }

        public void setResultsPerProvider(int resultsPerProvider) {
            this.resultsPerProvider = resultsPerProvider;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }
    }

    public static class Mistral {
        private String apiKey = "";
        private String model = "mistral-medium-latest";
        private String baseUrl = "https://api.mistral.ai";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Gemini {
        private String apiKey = "";
        private String model = "gemini-3.1-flash-lite";
        private String baseUrl = "https://generativelanguage.googleapis.com";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class OpenAi {
        private String apiKey = "";
        private String model = "gpt-6.2-astra";
        private String baseUrl = "https://api.openai.com";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class LogReport {
        private boolean enabled = true;
        private String libraryId = "";
        private int tailLines = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLibraryId() {
            return libraryId;
        }

        public void setLibraryId(String libraryId) {
            this.libraryId = libraryId;
        }

        public int getTailLines() {
            return tailLines;
        }

        public void setTailLines(int tailLines) {
            this.tailLines = tailLines;
        }
    }

    public static class Evaluation {
        private String provider = "mistral";
        private int minScore = 70;
        private int maxCandidates = 25;
        private int maxItemsPerRun = 5;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public int getMinScore() {
            return minScore;
        }

        public void setMinScore(int minScore) {
            this.minScore = minScore;
        }

        public int getMaxCandidates() {
            return maxCandidates;
        }

        public void setMaxCandidates(int maxCandidates) {
            this.maxCandidates = maxCandidates;
        }

        public int getMaxItemsPerRun() {
            return maxItemsPerRun;
        }

        public void setMaxItemsPerRun(int maxItemsPerRun) {
            this.maxItemsPerRun = maxItemsPerRun;
        }
    }

    public static class Portal {
        private String url = "";
        private String docsUrl = "";
        private String docsCron = "0 17 * * * *";
        private int maxQuestionsPerRun = 3;
        private String method = "POST";
        private String authHeaderName = "Authorization";
        private String authToken = "";
        private Map<String, String> headers = new LinkedHashMap<>();
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(30);

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getDocsUrl() {
            return docsUrl;
        }

        public void setDocsUrl(String docsUrl) {
            this.docsUrl = docsUrl;
        }

        public String getDocsCron() {
            return docsCron;
        }

        public void setDocsCron(String docsCron) {
            this.docsCron = docsCron;
        }

        public int getMaxQuestionsPerRun() {
            return maxQuestionsPerRun;
        }

        public void setMaxQuestionsPerRun(int maxQuestionsPerRun) {
            this.maxQuestionsPerRun = maxQuestionsPerRun;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getAuthHeaderName() {
            return authHeaderName;
        }

        public void setAuthHeaderName(String authHeaderName) {
            this.authHeaderName = authHeaderName;
        }

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public static class Store {
        private String path = "./data/processed-items.json";

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

    public static class Update {
        private boolean enabled = true;
        private String repo = "FrankS68/Freya";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRepo() {
            return repo;
        }

        public void setRepo(String repo) {
            this.repo = repo;
        }
    }
}

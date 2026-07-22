/*
 * AzureBranches - Global Configuration
 * Self-contained TOML config loader. No external dependencies.
 */
package com.azurebranches.config;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

public final class AzureBranchesConfig {

    private static final String FILE_NAME = "azurebranches_global_config.toml";
    private static AzureBranchesConfig INSTANCE;

    private final Path filePath;
    private final Map<String, Object> values = new LinkedHashMap<>();

    private int ioPoolThreads;
    private long ioPoolHoldTimeMs;
    private boolean chunkIoAsyncEnabled;
    private long debounceWindowMs;
    private boolean predictiveLoadEnabled;
    private double predictionSeconds;
    private int preloadRadius;
    private double minSpeed;
    private boolean entityLimitsEnabled;
    private final Map<String, Map<String, Object>> entityLimitTypes = new LinkedHashMap<>();
    private String commandBlocksMode;
    private long expRemoteTimeoutMs;
    private String expSuccessCountMode;
    private int expBatchMaxSize;
    private boolean expPhaseSnapshotEnabled;
    private boolean expValidationEnabled;
    private int expValidationMaxRetries;
    private int expValidationMaxReadSet;
    private boolean exp4DataInterceptEnabled;

    private AzureBranchesConfig(Path serverRoot) throws IOException {
        this.filePath = serverRoot.resolve(FILE_NAME);
        load();
        readValues();
    }

    public static synchronized AzureBranchesConfig init(Path serverRoot) {
        if (INSTANCE != null) throw new IllegalStateException("Already initialized");
        try { INSTANCE = new AzureBranchesConfig(serverRoot); }
        catch (IOException e) { throw new RuntimeException("Config init failed", e); }
        return INSTANCE;
    }

    public static AzureBranchesConfig get() {
        if (INSTANCE == null) throw new IllegalStateException("Not initialized");
        return INSTANCE;
    }

    public synchronized void reload() {
        try { load(); readValues(); }
        catch (IOException e) { System.err.println("[AzureBranches] reload failed: " + e.getMessage()); }
    }

    public void save() {
        try { writeFile(); }
        catch (IOException e) { System.err.println("[AzureBranches] save failed: " + e.getMessage()); }
    }

    private void load() throws IOException {
        values.clear();
        File file = filePath.toFile();
        if (!file.exists()) { file.getParentFile().mkdirs(); writeDefaults(); writeFile(); return; }
        String section = "";
        int parsedKeys = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[") && line.endsWith("]")) { section = line.substring(1, line.length()-1).trim(); continue; }
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String raw = line.substring(eq+1).trim();
                int hash = raw.indexOf('#');
                if (hash >= 0) raw = raw.substring(0, hash).trim();
                String fullKey = section.isEmpty() ? key : section + "." + key;
                Object val = parseValue(raw);
                if (val != null) { values.put(fullKey, val); parsedKeys++; }
                if (raw.startsWith("{") && raw.endsWith("}")) parseInlineTable(fullKey, raw);
            }
        }
        if (parsedKeys == 0) { writeDefaults(); writeFile(); }
    }

    private Object parseValue(String raw) {
        if (raw.isEmpty()) return null;
        if ("true".equals(raw)) return true;
        if ("false".equals(raw)) return false;
        try { return Long.parseLong(raw); } catch (NumberFormatException e) {}
        try { return Double.parseDouble(raw); } catch (NumberFormatException e) {}
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length()>=2) return raw.substring(1, raw.length()-1);
        return raw;
    }

    private void parseInlineTable(String fullKey, String raw) {
        String inner = raw.substring(1, raw.length()-1).trim();
        Map<String, Object> sub = new LinkedHashMap<>();
        for (String part : splitTopLevel(inner, ',')) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            Object val = parseValue(part.substring(eq+1).trim());
            if (val != null) sub.put(part.substring(0, eq).trim(), val);
        }
        if (!sub.isEmpty()) values.put(fullKey, sub);
    }

    private static List<String> splitTopLevel(String s, char sep) {
        List<String> r = new ArrayList<>();
        int d=0, st=0;
        for (int i=0; i<s.length(); i++) {
            char c = s.charAt(i);
            if (c=='{') d++; else if (c=='}') d--;
            else if (c==sep && d==0) { r.add(s.substring(st,i).trim()); st=i+1; }
        }
        r.add(s.substring(st).trim()); return r;
    }

    private void writeFile() throws IOException {
        try (PrintWriter w = new PrintWriter(new FileWriter(filePath.toFile()))) {
            w.println("# AzureBranches Global Configuration\n");
            Set<String> written = new HashSet<>();
            for (Map.Entry<String,Object> e : values.entrySet()) {
                String key = e.getKey();
                int ld = key.lastIndexOf('.');
                String s = ld>0 ? key.substring(0,ld) : "";
                String n = ld>0 ? key.substring(ld+1) : key;
                if (!written.contains(key)) {
                    if (!s.isEmpty()) w.println("\n["+s+"]");
                    w.print(n+" = "); writeVal(w, e.getValue()); w.println();
                    written.add(key);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void writeVal(PrintWriter w, Object v) {
        if (v instanceof Boolean || v instanceof Long || v instanceof Double) w.print(v);
        else if (v instanceof Map) {
            Map<String,Object> m = (Map<String,Object>)v;
            w.print("{ "); int i=0;
            for (Map.Entry<String,Object> e : m.entrySet()) {
                if (i++>0) w.print(", ");
                w.print(e.getKey()+" = "); writeVal(w, e.getValue());
            }
            w.print(" }");
        } else w.print("\""+v+"\"");
    }

    private void readValues() {
        ioPoolThreads=getInt("worker_pools.io.threads",ioPoolThreads());
        ioPoolHoldTimeMs=getLong("worker_pools.io.hold_time_ms",25L);
        chunkIoAsyncEnabled=getBool("chunk_io.async_enabled",true);
        debounceWindowMs=getLong("chunk_io.debounce.window_ms",100L);
        predictiveLoadEnabled=getBool("chunk_io.predictive_load.enabled",true);
        predictionSeconds=getDouble("chunk_io.predictive_load.prediction_seconds",2.0);
        preloadRadius=getInt("chunk_io.predictive_load.preload_radius",3);
        minSpeed=getDouble("chunk_io.predictive_load.min_speed",4.0);
        entityLimitsEnabled=getBool("entity_limits.enabled",false);
        commandBlocksMode=getString("command_blocks.mode","SAFE");
        expRemoteTimeoutMs=getLong("command_blocks.exp.remote_timeout_ms",1000L);
        expSuccessCountMode=getString("command_blocks.exp.success_count_mode","SUM");
        expBatchMaxSize=getInt("command_blocks.exp.batch_max_size",15);
        expPhaseSnapshotEnabled=getBool("command_blocks.exp.phase_snapshot.enabled",true);
        expValidationEnabled=getBool("command_blocks.exp.validation.enabled",true);
        expValidationMaxRetries=getInt("command_blocks.exp.validation.max_retries",3);
        expValidationMaxReadSet=getInt("command_blocks.exp.validation.max_read_set",256);
        exp4DataInterceptEnabled=getBool("command_blocks.exp4.data_intercept.enabled",true);
        entityLimitTypes.clear();
        for (Map.Entry<String,Object> e : values.entrySet()) {
            if (e.getKey().startsWith("entity_limits.types.") && e.getValue() instanceof Map) {
                entityLimitTypes.put(e.getKey().substring("entity_limits.types.".length()), (Map<String,Object>)e.getValue());
            }
        }
    }

    private int getInt(String k, int d) { Object v=values.get(k); if(v instanceof Long)return((Long)v).intValue(); if(v instanceof Double)return((Double)v).intValue(); if(v instanceof String)try{return Integer.parseInt((String)v);}catch(Exception e){} return d; }
    private long getLong(String k, long d) { Object v=values.get(k); if(v instanceof Long)return(Long)v; if(v instanceof Double)return((Double)v).longValue(); return d; }
    private double getDouble(String k, double d) { Object v=values.get(k); if(v instanceof Double)return(Double)v; if(v instanceof Long)return((Long)v).doubleValue(); return d; }
    private boolean getBool(String k, boolean d) { Object v=values.get(k); if(v instanceof Boolean)return(Boolean)v; return d; }
    private String getString(String k, String d) { Object v=values.get(k); return v!=null ? String.valueOf(v) : d; }

    private void writeDefaults() {
        setDefault("worker_pools.io.threads",(long)ioPoolThreads());
        setDefault("worker_pools.io.hold_time_ms",25L);
        setDefault("chunk_io.async_enabled",true);
        setDefault("chunk_io.debounce.window_ms",100L);
        setDefault("chunk_io.predictive_load.enabled",true);
        setDefault("chunk_io.predictive_load.prediction_seconds",2.0);
        setDefault("chunk_io.predictive_load.preload_radius",3L);
        setDefault("chunk_io.predictive_load.min_speed",4.0);
        setDefault("entity_limits.enabled",false);
        setDefault("command_blocks.mode","SAFE");
        setDefault("command_blocks.exp.remote_timeout_ms",1000L);
        setDefault("command_blocks.exp.success_count_mode","SUM");
        setDefault("command_blocks.exp.batch_max_size",15);
        setDefault("command_blocks.exp.phase_snapshot.enabled",true);
        setDefault("command_blocks.exp.validation.enabled",true);
        setDefault("command_blocks.exp.validation.max_retries",3L);
        setDefault("command_blocks.exp.validation.max_read_set",256L);
        setDefault("command_blocks.exp4.data_intercept.enabled",true);
    }

    private void setDefault(String key, Object value) { if(!values.containsKey(key))values.put(key,value); }

    public static int ioPoolThreads() { return Math.max(1,Runtime.getRuntime().availableProcessors()/4); }
    public int ioPoolThreadsConfig() { return ioPoolThreads; }
    public long ioPoolHoldTimeMs() { return ioPoolHoldTimeMs; }
    public boolean chunkIoAsyncEnabled() { return chunkIoAsyncEnabled; }
    public long debounceWindowMs() { return debounceWindowMs; }
    public boolean predictiveLoadEnabled() { return predictiveLoadEnabled; }
    public double predictionSeconds() { return predictionSeconds; }
    public int preloadRadius() { return preloadRadius; }
    public double minSpeed() { return minSpeed; }
    public boolean entityLimitsEnabled() { return entityLimitsEnabled; }
    public Map<String, Map<String, Object>> entityLimitTypes() { return entityLimitTypes; }
    public String commandBlocksMode() { return commandBlocksMode; }
    public long expRemoteTimeoutMs() { return expRemoteTimeoutMs; }
    public String expSuccessCountMode() { return expSuccessCountMode; }
    public int expBatchMaxSize() { return expBatchMaxSize; }
    public boolean expPhaseSnapshotEnabled() { return expPhaseSnapshotEnabled; }
    public boolean expValidationEnabled() { return expValidationEnabled; }
    public int expValidationMaxRetries() { return expValidationMaxRetries; }
    public int expValidationMaxReadSet() { return expValidationMaxReadSet; }
    public boolean exp4DataInterceptEnabled() { return exp4DataInterceptEnabled; }
}

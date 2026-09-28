package br.com.fiap.fiapx.processing.infrastructure.media;

import java.io.*;
import java.util.List;

public interface MediaProcess {
    void run(List<String> command, ExecutionBudget budget, OutputReader reader) throws IOException;
    @FunctionalInterface interface OutputReader { void read(InputStream stream) throws IOException; }
}

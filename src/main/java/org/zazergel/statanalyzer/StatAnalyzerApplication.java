package org.zazergel.statanalyzer;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.router.PageTitle;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

@Push
@PageTitle("StatAnalyzer")
@SpringBootApplication

public class StatAnalyzerApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(StatAnalyzerApplication.class, args);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Path dbPath = Paths.get("./db");
                if (Files.exists(dbPath)) {
                    Files.walkFileTree(dbPath, new SimpleFileVisitor<>() {
                        @Override
                        public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
                            Files.delete(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public @NonNull FileVisitResult postVisitDirectory(@NonNull Path dir, IOException exc) throws IOException {
                            Files.delete(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            } catch (Exception ignored) {
            }
        }));
    }
}

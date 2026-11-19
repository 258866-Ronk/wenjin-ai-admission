package cn.wenjin.importer;

import cn.wenjin.admission.WenjinApplication;
import cn.wenjin.admission.data.AdmissionDataImporter;
import cn.wenjin.admission.data.AdmissionDataImporter.ImportOptions;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class DataImporterApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(WenjinApplication.class, ImportCommand.class)
                .web(WebApplicationType.NONE)
                .properties("app.importer.seed-enabled=false")
                .run(args);
    }

    @Configuration
    static class ImportCommand {
        @Bean CommandLineRunner runImport(AdmissionDataImporter importer, Environment environment) {
            return args -> {
                String rawPath = environment.getProperty("import.path");
                if (rawPath == null || rawPath.isBlank()) {
                    throw new IllegalArgumentException("--import.path is required");
                }
                Path input = Path.of(rawPath).toAbsolutePath().normalize();
                if (!Files.exists(input)) throw new IllegalArgumentException("Import path does not exist: " + input);
                boolean dryRun = environment.getProperty("import.dry-run", Boolean.class, false);
                String dataset = environment.getProperty("import.dataset-version", "manual-import");
                List<String> provinces = csv(environment.getProperty("import.provinces"));
                List<Integer> years = csv(environment.getProperty("import.years")).stream().map(Integer::valueOf).toList();
                List<String> types = csv(environment.getProperty("import.data-types"));
                List<Path> files;
                if (Files.isDirectory(input)) {
                    try (var stream = Files.walk(input)) {
                        files = stream.filter(Files::isRegularFile).filter(file -> file.getFileName().toString().endsWith(".json")).sorted().toList();
                    }
                } else {
                    files = List.of(input);
                }
                int read = 0, inserted = 0, updated = 0, skipped = 0, duplicates = 0, failures = 0;
                for (Path file : files) {
                    var result = importer.importResource(new FileSystemResource(file),
                            new ImportOptions(dataset, dryRun, provinces, years, types));
                    read += result.readRecords(); inserted += result.insertedRecords(); updated += result.updatedRecords();
                    skipped += result.skippedRecords(); duplicates += result.duplicateRecords(); failures += result.failures().size();
                }
                System.out.printf("IMPORT_RESULT files=%d read=%d inserted=%d updated=%d skipped=%d duplicates=%d failures=%d dryRun=%s%n",
                        files.size(), read, inserted, updated, skipped, duplicates, failures, dryRun);
            };
        }

        private static List<String> csv(String value) {
            return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }
}


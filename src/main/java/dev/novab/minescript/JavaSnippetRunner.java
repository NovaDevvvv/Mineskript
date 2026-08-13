package dev.novab.minescript;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

public final class JavaSnippetRunner {
	private static final int MAX_SOURCE_LENGTH = 32_000;
	private static final String GENERATED_PACKAGE = "dev.novab.minescript.generated";

	public JsonElement run(String source, MinecraftClient client, ClientPlayerEntity player) {
		if (source == null || source.isBlank()) {
			throw new IllegalArgumentException("Java source is empty");
		}
		if (source.length() > MAX_SOURCE_LENGTH) {
			throw new IllegalArgumentException("Java source exceeds " + MAX_SOURCE_LENGTH + " characters");
		}

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new IllegalStateException("Java compilation requires Minecraft to run with a JDK, not a JRE");
		}

		String className = "Snippet" + UUID.randomUUID().toString().replace("-", "");
		Path outputDirectory;
		try {
			outputDirectory = Files.createTempDirectory("minescript-java-");
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create a temporary Java compiler directory", exception);
		}

		try {
			Path sourceDirectory = outputDirectory.resolve(GENERATED_PACKAGE.replace('.', File.separatorChar));
			Files.createDirectories(sourceDirectory);
			Path sourceFile = sourceDirectory.resolve(className + ".java");
			Files.writeString(sourceFile, buildSource(className, source), StandardCharsets.UTF_8);
			compile(compiler, sourceFile, outputDirectory);
			return execute(outputDirectory, className, client, player);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to prepare Java source", exception);
		} finally {
			deleteDirectory(outputDirectory);
		}
	}

	private String buildSource(String className, String body) {
		return """
			package %s;

			import com.google.gson.JsonArray;
			import com.google.gson.JsonElement;
			import com.google.gson.JsonNull;
			import com.google.gson.JsonObject;
			import com.google.gson.JsonPrimitive;
			import dev.novab.minescript.ClientJavaSnippet;
			import net.minecraft.client.MinecraftClient;
			import net.minecraft.client.network.ClientPlayerEntity;

			public final class %s implements ClientJavaSnippet {
				@Override
				public JsonElement run(MinecraftClient client, ClientPlayerEntity player) throws Exception {
			%s
				}
			}
			""".formatted(GENERATED_PACKAGE, className, indent(body));
	}

	private void compile(JavaCompiler compiler, Path sourceFile, Path outputDirectory) {
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjects(sourceFile.toFile());
			List<String> options = List.of("-classpath", compilerClassPath(), "-d", outputDirectory.toString());
			Boolean compiled = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
			if (!Boolean.TRUE.equals(compiled)) {
				throw new IllegalArgumentException("Java compilation failed:\n" + formatDiagnostics(diagnostics));
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to compile Java source", exception);
		}
	}

	private JsonElement execute(Path outputDirectory, String className, MinecraftClient client, ClientPlayerEntity player) {
		try (URLClassLoader loader = new URLClassLoader(
			new URL[] { outputDirectory.toUri().toURL() },
			ClientJavaSnippet.class.getClassLoader()
		)) {
			Class<?> snippetClass = Class.forName(GENERATED_PACKAGE + "." + className, true, loader);
			Object instance = snippetClass.getDeclaredConstructor().newInstance();
			if (!(instance instanceof ClientJavaSnippet snippet)) {
				throw new IllegalStateException("Compiled class does not implement ClientJavaSnippet");
			}

			JsonElement result = snippet.run(client, player);
			return result == null ? JsonNull.INSTANCE : result;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Unable to load compiled Java source", exception);
		} catch (Exception exception) {
			throw new IllegalStateException("Java source failed: " + exception.getMessage(), exception);
		}
	}

	private String compilerClassPath() {
		Set<String> entries = new LinkedHashSet<>();
		String classPath = System.getProperty("java.class.path", "");
		for (String entry : classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
			if (!entry.isBlank()) {
				entries.add(entry);
			}
		}
		addCodeSource(entries, ClientJavaSnippet.class);
		addCodeSource(entries, MinecraftClient.class);
		addCodeSource(entries, JsonElement.class);
		return String.join(File.pathSeparator, entries);
	}

	private void addCodeSource(Set<String> entries, Class<?> type) {
		CodeSource codeSource = type.getProtectionDomain().getCodeSource();
		if (codeSource == null || codeSource.getLocation() == null) {
			return;
		}
		try {
			entries.add(Path.of(codeSource.getLocation().toURI()).toString());
		} catch (Exception exception) {
			MinescriptClient.LOGGER.debug("Unable to determine Java compiler classpath for {}", type.getName(), exception);
		}
	}

	private String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
		StringBuilder result = new StringBuilder();
		for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
			result.append("line ")
				.append(diagnostic.getLineNumber())
				.append(": ")
				.append(diagnostic.getMessage(null))
				.append('\n');
		}
		return result.toString().trim();
	}

	private String indent(String source) {
		return source.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\n\t\t");
	}

	private void deleteDirectory(Path directory) {
		try (Stream<Path> paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException exception) {
					MinescriptClient.LOGGER.debug("Unable to remove temporary Java source {}", path, exception);
				}
			});
		} catch (IOException exception) {
			MinescriptClient.LOGGER.debug("Unable to remove temporary Java compiler directory {}", directory, exception);
		}
	}
}

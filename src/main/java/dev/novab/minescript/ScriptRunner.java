package dev.novab.minescript;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public final class ScriptRunner {
	private static final String CONTROL_PREFIX = "__MINESCRIPT_CONTROL__:";
	private static final String DISABLE_LOG = "DISABLE_LOG";
	private static final String ENABLE_LOG = "ENABLE_LOG";
	private static final Gson GSON = new Gson();
	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
	private static final long MAX_LOG_SIZE_BYTES = 1_000_000L;
	private static final int MAX_LOG_FILES = 3;

	private final MinescriptConfig config;

	public ScriptRunner(MinescriptConfig config) {
		this.config = config;
	}

	public CompletableFuture<Void> runScript(String scriptName) {
		return CompletableFuture.runAsync(() -> runScriptBlocking(scriptName));
	}

	public CompletableFuture<Integer> runAllScripts() {
		return CompletableFuture.supplyAsync(this::runAllScriptsBlocking);
	}

	private int runAllScriptsBlocking() {
		List<Path> scripts;
		try (Stream<Path> stream = Files.walk(this.config.scriptsDir())) {
			scripts = stream
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".py"))
				.sorted(Comparator.comparing(path -> this.config.scriptsDir().relativize(path).toString().toLowerCase()))
				.collect(Collectors.toList());
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to list Python scripts", exception);
		}

		for (Path script : scripts) {
			runScriptBlocking(this.config.scriptsDir().relativize(script).toString());
		}

		return scripts.size();
	}

	private void runScriptBlocking(String scriptName) {
		ScriptResolution resolution = resolveScript(scriptName);
		Path resolvedPath = resolution.path();
		String resolvedName = resolution.relativePath();

		if (resolution.autocorrected()) {
			emit(resolvedName, "Autocorrected \"" + scriptName + "\" to \"" + resolvedName + "\"", "SYSTEM", new ChatLoggingState());
		}

		List<String> command = new ArrayList<>();
		command.add(this.config.pythonCommand());
		command.add(resolvedPath.toString());

		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.directory(this.config.scriptsDir().toFile());
		processBuilder.redirectErrorStream(true);
		processBuilder.environment().put("MINESCRIPT_PORT", String.valueOf(this.config.port()));
		processBuilder.environment().put("MINESCRIPT_CONTROL_PREFIX", CONTROL_PREFIX);
		processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
		processBuilder.environment().put("PYTHONPATH", buildPythonPath(processBuilder));

		try {
			MinescriptClient.showScriptToast("Minescript", resolvedName + " started", false);
			Process process = processBuilder.start();
			ChatLoggingState chatLoggingState = new ChatLoggingState();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					MinescriptClient.LOGGER.info("[python] {}", line);
					handleProcessLine(resolvedName, line, chatLoggingState);
				}
			}

			int exitCode = process.waitFor();
			if (exitCode != 0) {
				emit(resolvedName, "Python exited with code " + exitCode, "ERROR", chatLoggingState);
				MinescriptClient.showScriptToast("Minescript", resolvedName + " failed", true);
				throw new IllegalStateException("Python exited with code " + exitCode);
			}

			emit(resolvedName, "Script completed", "SYSTEM", chatLoggingState);
			MinescriptClient.showScriptToast("Minescript", resolvedName + " finished", false);
		} catch (IOException exception) {
			emit(resolvedName, "Unable to launch Python command: " + this.config.pythonCommand(), "ERROR", new ChatLoggingState());
			MinescriptClient.showScriptToast("Minescript", resolvedName + " failed to start", true);
			throw new IllegalStateException("Unable to launch Python command: " + this.config.pythonCommand(), exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			emit(resolvedName, "Python script execution was interrupted", "ERROR", new ChatLoggingState());
			MinescriptClient.showScriptToast("Minescript", resolvedName + " interrupted", true);
			throw new IllegalStateException("Python script execution was interrupted", exception);
		} finally {
			MinescriptClient.clearSyntheticInputs();
		}
	}

	private ScriptResolution resolveScript(String requestedName) {
		String sanitizedRequest = sanitizeRequestedName(requestedName);
		if (sanitizedRequest.isBlank()) {
			throw new IllegalArgumentException("Script name is empty");
		}

		Path exactPath = resolveExactScriptPath(sanitizedRequest);
		if (exactPath != null) {
			return new ScriptResolution(exactPath, toRelativeScriptPath(exactPath), false);
		}

		List<Path> scripts = collectScripts();
		CandidateMatch bestMatch = findBestMatch(sanitizedRequest, scripts);
		if (bestMatch != null) {
			return new ScriptResolution(bestMatch.path(), toRelativeScriptPath(bestMatch.path()), true);
		}

		throw new IllegalArgumentException("Script not found: " + requestedName);
	}

	private Path resolveExactScriptPath(String requestedName) {
		Path candidate = this.config.scriptsDir().resolve(requestedName).normalize();
		if (isValidScriptPath(candidate)) {
			return candidate;
		}

		if (!requestedName.toLowerCase(Locale.ROOT).endsWith(".py")) {
			Path withExtension = this.config.scriptsDir().resolve(requestedName + ".py").normalize();
			if (isValidScriptPath(withExtension)) {
				return withExtension;
			}
		}

		return null;
	}

	private boolean isValidScriptPath(Path path) {
		return path.startsWith(this.config.scriptsDir())
			&& path.toString().toLowerCase(Locale.ROOT).endsWith(".py")
			&& Files.isRegularFile(path);
	}

	private List<Path> collectScripts() {
		try (Stream<Path> stream = Files.walk(this.config.scriptsDir())) {
			return stream
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".py"))
				.collect(Collectors.toList());
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to list Python scripts", exception);
		}
	}

	private CandidateMatch findBestMatch(String requestedName, List<Path> scripts) {
		String requestKey = normalizeMatchKey(requestedName);
		String requestFileKey = normalizeMatchKey(fileNameWithoutExtension(requestedName));
		CandidateMatch bestMatch = null;

		for (Path script : scripts) {
			String relativePath = toRelativeScriptPath(script);
			String relativeKey = normalizeMatchKey(relativePath);
			String fileKey = normalizeMatchKey(fileNameWithoutExtension(relativePath));

			int score;
			if (relativeKey.equals(requestKey)) {
				score = 0;
			} else if (fileKey.equals(requestFileKey)) {
				score = 1;
			} else {
				int relativeDistance = levenshteinDistance(relativeKey, requestKey) + 2;
				int fileDistance = levenshteinDistance(fileKey, requestFileKey) + 3;
				score = Math.min(relativeDistance, fileDistance);
			}

			if (bestMatch == null || score < bestMatch.score() || (score == bestMatch.score() && relativePath.length() < toRelativeScriptPath(bestMatch.path()).length())) {
				bestMatch = new CandidateMatch(script, score);
			}
		}

		if (bestMatch == null) {
			return null;
		}

		int threshold = Math.max(2, Math.min(8, requestKey.length() / 3 + 1));
		return bestMatch.score() <= threshold ? bestMatch : null;
	}

	private String sanitizeRequestedName(String scriptName) {
		return scriptName.trim().replace('\\', '/');
	}

	private String toRelativeScriptPath(Path path) {
		return this.config.scriptsDir().relativize(path).toString().replace('\\', '/');
	}

	private String normalizeMatchKey(String value) {
		String withoutExtension = fileNameWithoutExtension(value).toLowerCase(Locale.ROOT).replace('\\', '/');
		StringBuilder builder = new StringBuilder(withoutExtension.length());
		for (int index = 0; index < withoutExtension.length(); index++) {
			char current = withoutExtension.charAt(index);
			if (Character.isLetterOrDigit(current) || current == '/') {
				builder.append(current);
			}
		}
		return builder.toString();
	}

	private String fileNameWithoutExtension(String value) {
		String normalized = value.replace('\\', '/');
		return normalized.toLowerCase(Locale.ROOT).endsWith(".py") ? normalized.substring(0, normalized.length() - 3) : normalized;
	}

	private int levenshteinDistance(String left, String right) {
		if (left.equals(right)) {
			return 0;
		}

		if (left.isEmpty()) {
			return right.length();
		}

		if (right.isEmpty()) {
			return left.length();
		}

		int[] previous = new int[right.length() + 1];
		int[] current = new int[right.length() + 1];

		for (int column = 0; column <= right.length(); column++) {
			previous[column] = column;
		}

		for (int row = 1; row <= left.length(); row++) {
			current[0] = row;
			for (int column = 1; column <= right.length(); column++) {
				int substitutionCost = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
				current[column] = Math.min(
					Math.min(current[column - 1] + 1, previous[column] + 1),
					previous[column - 1] + substitutionCost
				);
			}

			int[] swap = previous;
			previous = current;
			current = swap;
		}

		return previous[right.length()];
	}

	private void handleProcessLine(String scriptName, String line, ChatLoggingState chatLoggingState) {
		if (line.startsWith(CONTROL_PREFIX)) {
			handleControlPayload(scriptName, line.substring(CONTROL_PREFIX.length()).trim(), chatLoggingState);
			return;
		}

		emit(scriptName, line, "INFO", chatLoggingState);
	}

	private void handleControlPayload(String scriptName, String payload, ChatLoggingState chatLoggingState) {
		if (DISABLE_LOG.equals(payload)) {
			chatLoggingState.chatEnabled = false;
			return;
		}

		if (ENABLE_LOG.equals(payload)) {
			chatLoggingState.chatEnabled = true;
			return;
		}

		if (!payload.startsWith("{")) {
			return;
		}

		JsonObject json = GSON.fromJson(payload, JsonObject.class);
		if (json == null || !json.has("command")) {
			return;
		}

		String command = json.get("command").getAsString();
		switch (command) {
			case "set_chat_output" -> chatLoggingState.chatEnabled = !json.has("enabled") || json.get("enabled").getAsBoolean();
			case "log" -> emit(
				scriptName,
				json.has("message") ? json.get("message").getAsString() : "",
				json.has("level") ? json.get("level").getAsString() : "INFO",
				chatLoggingState
			);
			default -> {
			}
		}
	}

	private void emit(String scriptName, String message, String level, ChatLoggingState chatLoggingState) {
		String formattedLine = formatLogLine(level, message);
		appendToLogFile(scriptName, formattedLine);
		if (chatLoggingState.chatEnabled) {
			MinescriptClient.sendScriptMessage(scriptName, formattedLine, level);
		}
	}

	private String formatLogLine(String level, String message) {
		String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
		return "[" + timestamp + "] [" + level.toUpperCase() + "] " + message;
	}

	private synchronized void appendToLogFile(String scriptName, String line) {
		Path logFile = this.config.logsDir().resolve("minescript.log");
		try {
			Files.createDirectories(this.config.logsDir());
			rollLogsIfNeeded(logFile);
			Files.writeString(
				logFile,
				"[" + scriptName + "] " + line + System.lineSeparator(),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND
			);
		} catch (IOException exception) {
			MinescriptClient.LOGGER.warn("Failed to append Minescript log file", exception);
		}
	}

	private void rollLogsIfNeeded(Path logFile) throws IOException {
		if (!Files.exists(logFile) || Files.size(logFile) < MAX_LOG_SIZE_BYTES) {
			return;
		}

		for (int index = MAX_LOG_FILES; index >= 1; index--) {
			Path current = this.config.logsDir().resolve("minescript.log." + index);
			if (!Files.exists(current)) {
				continue;
			}

			if (index == MAX_LOG_FILES) {
				Files.delete(current);
			} else {
				Path next = this.config.logsDir().resolve("minescript.log." + (index + 1));
				Files.move(current, next, StandardCopyOption.REPLACE_EXISTING);
			}
		}

		Files.move(logFile, this.config.logsDir().resolve("minescript.log.1"), StandardCopyOption.REPLACE_EXISTING);
	}

	private String buildPythonPath(ProcessBuilder processBuilder) {
		String existing = processBuilder.environment().getOrDefault("PYTHONPATH", "");
		String separator = System.getProperty("path.separator", ";");
		if (existing.isBlank()) {
			return this.config.pythonDir().toString();
		}

		return this.config.pythonDir() + separator + existing;
	}

	private static final class ChatLoggingState {
		private boolean chatEnabled = true;
	}

	private record ScriptResolution(Path path, String relativePath, boolean autocorrected) {
	}

	private record CandidateMatch(Path path, int score) {
	}
}

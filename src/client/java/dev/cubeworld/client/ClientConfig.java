package dev.cubeworld.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The client options in {@code config/cubeworld-client.properties}. The file is written with the defaults on first start. */
public final class ClientConfig {
	private static final String SHOW_STORAGE_LINE = "showStorageLine";

	/** Whether the look, the walking yaw and the face of the player go to logs/cubeworld-look.log while the view frame is tilted. */
	public static final boolean traceLook = Boolean.parseBoolean(read().getProperty("traceLook", "false"));

	/** Whether F3 shows the line with the storage position, which is only of use when debugging the mod. */
	public static final boolean showStorageLine = Boolean.parseBoolean(read().getProperty(SHOW_STORAGE_LINE, "false"));

	private ClientConfig() {
	}

	private static Properties read() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("cubeworld-client.properties");
		Properties properties = new Properties();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				properties.load(reader);
			} catch (IOException e) {
				return properties;
			}
		}
		if (properties.getProperty(SHOW_STORAGE_LINE) == null) {
			properties.setProperty(SHOW_STORAGE_LINE, "false");
			try (Writer writer = Files.newBufferedWriter(file)) {
				properties.store(writer, "Cube World client options. showStorageLine: show the storage position on the F3 screen.");
			} catch (IOException ignored) {
				// The default applies when the file cannot be written.
			}
		}
		return properties;
	}
}

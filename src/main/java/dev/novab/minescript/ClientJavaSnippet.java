package dev.novab.minescript;

import com.google.gson.JsonElement;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

public interface ClientJavaSnippet {
	JsonElement run(MinecraftClient client, ClientPlayerEntity player) throws Exception;
}

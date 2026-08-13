package dev.novab.minescript;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

public final class ScriptToast implements Toast {
	private static final long DISPLAY_TIME_MILLIS = 5_000L;

	private final Text title;
	private final Text description;
	private final ItemStack icon;

	public ScriptToast(Text title, Text description, ItemStack icon) {
		this.title = title;
		this.description = description;
		this.icon = icon;
	}

	@Override
	public Visibility draw(DrawContext context, ToastManager manager, long startTime) {
		MinecraftClient client = manager.getClient();
		context.fill(0, 0, getWidth(), getHeight(), 0xE0181818);
		context.drawBorder(0, 0, getWidth(), getHeight(), 0xFFAAAAAA);
		context.drawItemWithoutEntity(this.icon, 8, 8);
		context.drawTextWithShadow(client.textRenderer, this.title, 32, 8, 0xFFFFFFFF);
		if (this.description != null) {
			context.drawTextWithShadow(client.textRenderer, this.description, 32, 22, 0xFFD0D0D0);
		}
		return startTime >= DISPLAY_TIME_MILLIS ? Visibility.HIDE : Visibility.SHOW;
	}
}
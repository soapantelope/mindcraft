package mindcraft.mixin.client;

import mindcraft.client.MindCraftSettingsScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.registry.RegistryKey;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.gui.screen.world.CreateWorldScreen$WorldTab")
public abstract class CreateWorldScreenWorldTabMixin {

    @Shadow @Final
    CreateWorldScreen field_42182;

    @Shadow
    private ButtonWidget customizeButton;

    private static final RegistryKey<WorldPreset> MINDCRAFT_PRESET =
            RegistryKey.of(RegistryKeys.WORLD_PRESET, Identifier.of("mindcraft", "world"));

    @Inject(method = "method_48676", at = @At("TAIL"))
    private void mindcraft$enableCustomizeButton(WorldCreator worldCreator, CallbackInfo ci) {
        if (isMindCraftWorld()) customizeButton.active = true;
    }

    @Inject(method = "method_48680", at = @At("HEAD"), cancellable = true)
    private void mindcraft$forceCustomizeAvailable(CallbackInfoReturnable<Boolean> cir) {
        if (isMindCraftWorld()) cir.setReturnValue(true);
    }

    @Inject(method = "method_48681", at = @At("HEAD"), cancellable = true)
    private void mindcraft$forceCustomizeVisible(CallbackInfoReturnable<Boolean> cir) {
        if (isMindCraftWorld()) cir.setReturnValue(true);
    }

    @Inject(method = "openCustomizeScreen", at = @At("HEAD"), cancellable = true)
    private void mindcraft$openSettingsScreen(CallbackInfo ci) {
        if (!isMindCraftWorld()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            mc.setScreen(new MindCraftSettingsScreen(field_42182));
            ci.cancel();
        }
    }

    private boolean isMindCraftWorld() {
        WorldCreator creator = field_42182.getWorldCreator();
        if (creator == null) return false;
        WorldCreator.WorldType worldType = creator.getWorldType();
        if (worldType == null) return false;
        if (MINDCRAFT_PRESET.equals(worldType.preset())) return true;
        String name = worldType.getName().getString();
        return "MindCraft".equalsIgnoreCase(name) || "mindcraft:world".equalsIgnoreCase(name);
    }
}

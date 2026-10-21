package com.example.sealmod.mixin;

import com.example.sealmod.Hooks;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A mixin, in Java, as Mixin wants its classes (docs/JAVA.md §11): it puts a call to the mod's
// Seal code at the start of Minecraft's DedicatedServerSettings.forceSave(), which the server
// runs as it starts, to write server.properties. sealmod.mixins.json names it.
@Mixin(DedicatedServerSettings.class)
abstract class SettingsMixin {
    @Inject(method = "forceSave", at = @At("HEAD"))
    private void sealmod$forceSave(CallbackInfo ci) {
        Hooks.savingSettings((DedicatedServerSettings) (Object) this);
    }
}

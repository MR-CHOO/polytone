package net.mehvahdjukaar.polytone.mixins.accessor;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/**
 * Reads the set of external targets a post chain references.
 *
 * <p>This is the targets the chain ACTUALLY uses, not the ones it was permitted to use:
 * {@code PostChain.load} streams the chain's referenced target ids, drops the ones the config declares
 * as internal targets, and stores the remainder - the permitted set it is handed is only used to
 * reject unknown ids. That makes this the right signal for deciding which render stage a chain can run
 * in, since a stage can only host a chain whose external targets it is able to supply.</p>
 */
@Mixin(PostChain.class)
public interface PostChainAccessor {

    @Accessor("externalTargets")
    Set<Identifier> polytone$getExternalTargets();
}

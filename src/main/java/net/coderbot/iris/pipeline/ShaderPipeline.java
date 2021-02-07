package net.coderbot.iris.pipeline;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.gl.program.Program;
import net.coderbot.iris.gl.program.ProgramBuilder;
import net.coderbot.iris.layer.GbufferProgram;
import net.coderbot.iris.rendertarget.NoiseTexture;
import net.coderbot.iris.rendertarget.RenderTargets;
import net.coderbot.iris.shaderpack.ShaderPack;
import net.coderbot.iris.uniforms.CommonUniforms;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL20C;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlProgramManager;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.util.Identifier;

/**
 * Encapsulates the compiled shader program objects for the currently loaded shaderpack.
 */
public class ShaderPipeline {
	private final RenderTargets renderTargets;
	@Nullable
	private final Pass basic;
	@Nullable
	private final Pass textured;
	@Nullable
	private final Pass texturedLit;
	@Nullable
	private final Pass skyBasic;
	@Nullable
	private final Pass skyTextured;
	@Nullable
	private final Pass clouds;
	@Nullable
	private final Pass terrain;
	@Nullable
	private final Pass translucent;
	@Nullable
	private final Pass damagedBlock;
	@Nullable
	private final Pass weather;
	@Nullable
	private final Pass beaconBeam;
	@Nullable
	private final Pass entities;
	@Nullable
	private final Pass blockEntities;
	@Nullable
	private final Pass glowingEntities;
	@Nullable
	private final Pass glint;
	@Nullable
	private final Pass eyes;
	@Nullable
	private final Pass shadows;

	private final GlFramebuffer clearAltBuffers;
	private final GlFramebuffer clearMainBuffers;
	private final GlFramebuffer baseline;
	private final GlFramebuffer shadowBuffer;

	private final NoiseTexture noiseTexture;
	private final int waterId;

	public static ProgramBuilder CURRENT_BUILDER;

	public ShaderPipeline(ShaderPack pack, RenderTargets renderTargets) {
		this.renderTargets = renderTargets;
		waterId = pack.getIdMap().getBlockProperties().getOrDefault(new Identifier("minecraft", "water"), -1);

		this.basic = pack.getGbuffersBasic().map(this::createDefaultPass).orElse(null);
		this.textured = pack.getGbuffersTextured().map(this::createDefaultPass).orElse(basic);
		this.texturedLit = pack.getGbuffersTexturedLit().map(this::createDefaultPass).orElse(textured);
		this.skyBasic = pack.getGbuffersSkyBasic().map(this::createDefaultPass).orElse(basic);
		this.skyTextured = pack.getGbuffersSkyTextured().map(this::createDefaultPass).orElse(textured);
		this.clouds = pack.getGbuffersClouds().map(this::createDefaultPass).orElse(textured);
		this.terrain = pack.getGbuffersTerrain().map(this::createDefaultPass).orElse(texturedLit);
		this.translucent = pack.getGbuffersWater().map(this::createDefaultPass).orElse(terrain);
		this.damagedBlock = pack.getGbuffersDamagedBlock().map(this::createDefaultPass).orElse(terrain);
		// TODO: Load weather shaders
		this.weather = texturedLit;
		this.beaconBeam = pack.getGbuffersBeaconBeam().map(this::createDefaultPass).orElse(textured);
		this.entities = pack.getGbuffersEntities().map(this::createDefaultPass).orElse(texturedLit);
		this.blockEntities = pack.getGbuffersBlock().map(this::createDefaultPass).orElse(terrain);
		// TODO: Load glowing entities
		this.glowingEntities = entities;
		this.glint = pack.getGbuffersGlint().map(this::createDefaultPass).orElse(textured);
		this.eyes = pack.getGbuffersEntityEyes().map(this::createDefaultPass).orElse(textured);

		this.shadows = pack.getShadows().map(source -> createPass(source, renderTargets.createShadowFramebuffer(source.getDirectives().getDrawBuffers()))).orElse(texturedLit);

		int[] buffersToBeCleared = pack.getPackDirectives().getBuffersToBeCleared().toIntArray();

		this.clearAltBuffers = renderTargets.createFramebufferWritingToAlt(buffersToBeCleared);
		this.clearMainBuffers = renderTargets.createFramebufferWritingToMain(buffersToBeCleared);
		this.baseline = renderTargets.createFramebufferWritingToMain(new int[] {0});
		this.shadowBuffer = renderTargets.createShadowFramebuffer(new int[] {0});

		this.noiseTexture = new NoiseTexture(128, 128);
	}

	public void useProgram(GbufferProgram program) {
		if (!isRenderingWorld) {
			// don't mess with non-world rendering
			return;
		}

		switch (program) {
			case SHADOW:
				beginShadows();
				return;
			case TERRAIN:
				//RenderSystem.enableAlphaTest();
				//RenderSystem.alphaFunc(GL11.GL_GREATER, 0.1f);
				beginTerrain();
				return;
			case TRANSLUCENT_TERRAIN:
				beginTranslucentTerrain();
				return;
			case DAMAGED_BLOCKS:
				beginPass(damagedBlock);
				return;
			case BASIC:
				RenderSystem.enableAlphaTest();
				RenderSystem.alphaFunc(GL11.GL_GREATER, 0.1f);
				beginBasic();
				return;
			case BEACON_BEAM:
				beginPass(beaconBeam);
				return;
			case ENTITIES:
				// TODO: Disabling blend on entities is hardcoded for Sildur's
				//GlStateManager.disableBlend();
				//RenderSystem.enableAlphaTest();
				//RenderSystem.alphaFunc(GL11.GL_GREATER, 0.1f);
				beginPass(entities);
				return;
			case BLOCK_ENTITIES:
				beginPass(blockEntities);
				return;
			case ENTITIES_GLOWING:
				// TODO: Disabling blend on entities is hardcoded for Sildur's
				//GlStateManager.disableBlend();
				//RenderSystem.enableAlphaTest();
				//RenderSystem.alphaFunc(GL11.GL_GREATER, 0.1f);
				beginPass(glowingEntities);
				return;
			case EYES:
				beginPass(eyes);
				return;
			case ARMOR_GLINT:
				beginPass(glint);
				return;
		}

		// TODO
		throw new UnsupportedOperationException("TODO");
	}

	public boolean shouldDisableVanillaEntityShadows() {
		// TODO: Don't hardcode this for Sildur's
		// OptiFine seems to disable vanilla shadows when the shaderpack uses shadow mapping?
		return true;
	}

	private void beginPass(Pass pass) {
		if (pass != null) {
			pass.use();
		}
	}

	private Pass createDefaultPass(ShaderPack.ProgramSource source) {
		return createPass(source, renderTargets.createFramebufferWritingToMain(source.getDirectives().getDrawBuffers()));
	}

	private Pass createPass(ShaderPack.ProgramSource source, GlFramebuffer framebuffer) {
		// TODO: Properly handle empty shaders
		Objects.requireNonNull(source.getVertexSource());
		Objects.requireNonNull(source.getFragmentSource());
		ProgramBuilder builder;

		try {
			builder = ProgramBuilder.begin(source.getName(), source.getVertexSource().orElse(null),
					source.getFragmentSource().orElse(null));
		} catch (IOException e) {
			// TODO: Better error handling
			throw new RuntimeException("Shader compilation failed!", e);
		}

		CURRENT_BUILDER = builder;

		CommonUniforms.addCommonUniforms(builder, source.getParent().getIdMap());

		builder.bindAttributeLocation(10, "mc_Entity");
		builder.bindAttributeLocation(11, "mc_midTexCoord");
		builder.bindAttributeLocation(12, "at_tangent");

		return new Pass(builder.build(), framebuffer);
	}

	private final class Pass {
		private final Program program;
		private final GlFramebuffer framebuffer;

		private Pass(Program program, GlFramebuffer framebuffer) {
			this.program = program;
			this.framebuffer = framebuffer;
		}

		public void use() {
			// TODO: Binding the texture here is ugly and hacky. It would be better to have a utility function to set up
			// a given program and bind the required textures instead.
			GlStateManager.activeTexture(GL15C.GL_TEXTURE15);
			GlStateManager.bindTexture(noiseTexture.getTextureId());
			GlStateManager.activeTexture(GL15C.GL_TEXTURE0);
			framebuffer.bind();
			program.use();
		}

		public Program getProgram() {
			return program;
		}

		public void destroy() {
			this.program.destroy();
			this.framebuffer.destroy();
		}
	}

	public void destroy() {
		destroyPasses(basic, textured, texturedLit, skyBasic, skyTextured, clouds, terrain, translucent, weather, shadows);
		clearAltBuffers.destroy();
		clearMainBuffers.destroy();
		baseline.destroy();
		noiseTexture.destroy();
	}

	private static void destroyPasses(Pass... passes) {
		Set<Pass> destroyed = new HashSet<>();

		for (Pass pass : passes) {
			if (pass == null) {
				continue;
			}

			if (destroyed.contains(pass)) {
				continue;
			}

			pass.destroy();
			destroyed.add(pass);
		}
	}

	public void end() {
		if (!isRenderingWorld) {
			// don't mess with non-world rendering
			return;
		}

		// Disable any alpha func shenanigans
		RenderSystem.disableAlphaTest();
		RenderSystem.defaultAlphaFunc();

		if (this.basic == null) {
			GlProgramManager.useProgram(0);
			this.baseline.bind();

			return;
		}

		// Default to gbuffers_basic for unrecognized render layers
		// TODO: Potentially use gbuffers_textured or gbuffers_textured_lit appropriately?
		this.basic.use();
	}

	private static void setupAttributes(Pass pass) {
		// TODO: Properly add these attributes into the vertex format

		float blockId = -1.0F;

		setupAttribute(pass, "mc_Entity", blockId, -1.0F, -1.0F, -1.0F);
		setupAttribute(pass, "mc_midTexCoord", 0.0F, 0.0F, 0.0F, 0.0F);
		setupAttribute(pass, "at_tangent", 1.0F, 0.0F, 0.0F, 1.0F);
	}

	private static void setupAttribute(Pass pass, String name, float v0, float v1, float v2, float v3) {
		int location = GL20.glGetAttribLocation(pass.getProgram().getProgramId(), name);

		if (location != -1) {
			GL20.glVertexAttrib4f(location, v0, v1, v2, v3);
		}
	}

	public void prepareRenderTargets() {
		Framebuffer main = MinecraftClient.getInstance().getFramebuffer();
		renderTargets.resizeIfNeeded(main.textureWidth, main.textureHeight);

		clearMainBuffers.bind();
		RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
		RenderSystem.clear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);

		clearAltBuffers.bind();
		// Not clearing the depth buffer since there's only one of those and it was already cleared
		RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
		RenderSystem.clear(GL11C.GL_COLOR_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);

		// We only want the vanilla clear color to be applied to colortex0
		baseline.bind();
	}

	public void copyCurrentDepthTexture() {
		baseline.bind();
		GlStateManager.bindTexture(renderTargets.getDepthTextureNoTranslucents().getTextureId());
		GL20C.glCopyTexImage2D(GL20C.GL_TEXTURE_2D, 0, GL20C.GL_DEPTH_COMPONENT, 0, 0, renderTargets.getCurrentWidth(), renderTargets.getCurrentHeight(), 0);
	}

	public void copyCurrentShadowTextures() {
		shadowBuffer.bind();
		GlStateManager.bindTexture(renderTargets.getShadowTexture().getTextureId());
		GL20C.glCopyTexImage2D(GL20C.GL_TEXTURE_2D, 0, GL20C.GL_DEPTH_COMPONENT, 0, 0, renderTargets.getCurrentWidth(), renderTargets.getCurrentHeight(), 0);
		GlStateManager.bindTexture(renderTargets.getShadowTextureNoTranslucents().getTextureId());
		GL20C.glCopyTexImage2D(GL20C.GL_TEXTURE_2D, 0, GL20C.GL_DEPTH_COMPONENT, 0, 0, renderTargets.getCurrentWidth(), renderTargets.getCurrentHeight(), 0);
		//GlStateManager.bindTexture(renderTargets.getShadowColor().getTextureId());
		//GL20C.glCopyTexImage2D(GL20C.GL_TEXTURE_2D, 0, GL20C.GL_COLOR, 0, 0, renderTargets.getCurrentWidth(), renderTargets.getCurrentHeight(), 0);
	}

	public void beginClouds() {
		if (clouds == null) {
			return;
		}

		clouds.use();
	}

	public void endClouds() {
		end();
	}

	public void beginTerrain() {
		if (terrain == null) {
			return;
		}

		// TODO: Don't disable blend normally, this is hardcoding for Sildur's
		//GlStateManager.disableBlend();
		terrain.use();
		setupAttributes(terrain);
	}

	public void beginTranslucentTerrain() {
		if (translucent == null) {
			return;
		}

		translucent.use();
		setupAttributes(translucent);

		// TODO: This is just making it so that all translucent content renders like water. We need to properly support
		// mc_Entity!
		setupAttribute(translucent, "mc_Entity", waterId, -1.0F, -1.0F, -1.0F);
	}

	public void beginSky() {
		if (skyBasic == null) {
			return;
		}

		skyBasic.use();
	}

	public void beginTexturedSky() {
		if (skyTextured == null) {
			return;
		}

		skyTextured.use();
	}

	public void endTexturedSky() {
		if (skyBasic == null) {
			endSky();
		} else {
			skyBasic.use();
		}
	}

	public void endSky() {
		end();
	}

	public void beginWeather() {
		if (weather == null) {
			return;
		}

		weather.use();
	}

	public void endWeather() {
		end();
	}

	public void beginWorldBorder() {
		if (texturedLit == null) {
			return;
		}

		texturedLit.use();
	}

	public void endWorldBorder() {
		end();
	}

	public void beginBasic() {
		if (basic == null) {
			return;
		}

		// TODO: This is hardcoded for Sildur's, we shouldn't disable blend normally
		GlStateManager.disableBlend();
		basic.use();
	}

	public void beginParticleSheet(ParticleTextureSheet sheet) {
		Pass pass = textured;

		if (sheet == ParticleTextureSheet.PARTICLE_SHEET_OPAQUE || sheet == ParticleTextureSheet.TERRAIN_SHEET || sheet == ParticleTextureSheet.CUSTOM) {
			pass = texturedLit;
		}

		if (sheet == ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT) {
			// TODO: Should we be using some other pass? (gbuffers_water?)
			pass = texturedLit;
		}

		if (sheet == ParticleTextureSheet.PARTICLE_SHEET_LIT) {
			// Yes, this seems backwards. However, in this case, these particles are always bright regardless of the
			// lighting condition, and therefore don't use the textured_lit program.
			pass = textured;
		}

		if (pass != null) {
			pass.use();
		}
	}

	public void endParticles() {
		end();
	}

	public void beginShadows() {
		if (shadows == null) {
			return;
		}
		shadows.use();

		setupAttributes(shadows);
	}

	public void endShadows() {
		//System.out.println("ENDING SHADOWS");
		end();
	}

	// TODO: better way to avoid this global state?
	private boolean isRenderingWorld = false;

	public void beginWorldRender() {
		isRenderingWorld = true;
	}

	public void endWorldRender() {
		GlProgramManager.useProgram(0);
		isRenderingWorld = false;
	}
}

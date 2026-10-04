package com.bloom.client.render;

import com.bloom.BloomMod;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.EnumMap;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldTerrainRenderContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public final class BloomSourceRenderer {
	public static final ResourceLocation SOURCE_TARGET_ID = ResourceLocation.fromNamespaceAndPath(BloomMod.MOD_ID, "source");
	private static final ResourceLocation TERRAIN_VERTEX_SHADER_ID = ResourceLocation.withDefaultNamespace("core/terrain");
	private static final ResourceLocation BLOOM_TERRAIN_SOURCE_FRAGMENT_SHADER_ID = ResourceLocation.fromNamespaceAndPath(BloomMod.MOD_ID, "core/terrain_bloom_source");
	private static final ResourceLocation BLOOM_TERRAIN_SOLID_PIPELINE_ID = ResourceLocation.fromNamespaceAndPath(BloomMod.MOD_ID, "pipeline/bloom_terrain_solid");
	private static final ResourceLocation BLOOM_TERRAIN_CUTOUT_PIPELINE_ID = ResourceLocation.fromNamespaceAndPath(BloomMod.MOD_ID, "pipeline/bloom_terrain_cutout");
	private static final List<String> CHUNK_SECTION_UNIFORMS = List.of("DynamicTransforms");
	private static final float INTERNAL_POST_SCALE = 0.5f;
	private static final int BLOOM_ATTACHMENT = GL30.GL_COLOR_ATTACHMENT1;
	private static final int[] MRT_DRAW_BUFFERS = new int[] { GL30.GL_COLOR_ATTACHMENT0, BLOOM_ATTACHMENT };
	private static final RenderPipeline BLOOM_TERRAIN_SOLID_PIPELINE = buildTerrainSourcePipeline(BLOOM_TERRAIN_SOLID_PIPELINE_ID, false);
	private static final RenderPipeline BLOOM_TERRAIN_CUTOUT_PIPELINE = buildTerrainSourcePipeline(BLOOM_TERRAIN_CUTOUT_PIPELINE_ID, true);

	private static GpuTexture bloomAttachmentTexture;
	private static GpuTextureView bloomAttachmentView;
	private static AttachedBloomRenderTarget sourceTarget;
	private static boolean preparedThisFrame;
	private static boolean loggedBackendFailure;
	private static boolean loggedAttachmentFailure;

	private BloomSourceRenderer() {
	}

	public static void reset() {
		if (bloomAttachmentView != null) {
			bloomAttachmentView.close();
			bloomAttachmentView = null;
		}
		if (bloomAttachmentTexture != null) {
			bloomAttachmentTexture.close();
			bloomAttachmentTexture = null;
		}
		sourceTarget = null;
		preparedThisFrame = false;
		loggedAttachmentFailure = false;
	}

	public static void prepareSource(WorldTerrainRenderContext context) {
		preparedThisFrame = false;
		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget mainTarget = minecraft.getMainRenderTarget();
		if (mainTarget == null || mainTarget.width <= 0 || mainTarget.height <= 0) {
			return;
		}
		if (!ensureAttachment(mainTarget.width, mainTarget.height)) {
			return;
		}

		clearAttachment();
		if (sourceTarget == null) {
			sourceTarget = new AttachedBloomRenderTarget();
		}
		sourceTarget.setAttachment(mainTarget, bloomAttachmentTexture, bloomAttachmentView);
		preparedThisFrame = true;
	}

	public static RenderTarget getSourceTarget() {
		return preparedThisFrame ? sourceTarget : null;
	}

	public static boolean hasPreparedSourceThisFrame() {
		return preparedThisFrame && sourceTarget != null;
	}

	public static void replayVanillaOpaqueGroup(
		ChunkSectionLayerGroup group,
		EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> drawsPerLayer,
		int maxIndicesRequired,
		GpuBufferSlice[] dynamicTransforms
	) {
		if (!preparedThisFrame || group != ChunkSectionLayerGroup.OPAQUE || bloomAttachmentView == null || drawsPerLayer == null) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (SharedConstants.DEBUG_HOTKEYS && minecraft.wireframe) {
			return;
		}

		RenderTarget outputTarget = group.outputTarget();
		GpuTextureView depthTextureView = outputTarget.getDepthTextureView();
		if (depthTextureView == null) {
			return;
		}

		// 1.21.10's ChunkSectionsToRender does not carry the block atlas view, so look it up directly.
		AbstractTexture blockAtlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
		if (blockAtlas == null) {
			return;
		}
		GpuTextureView textureView = blockAtlas.getTextureView();
		if (textureView == null) {
			return;
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		try (
			RenderPass renderPass = encoder.createRenderPass(
				() -> "shine_bloom_source_" + group.label(),
				bloomAttachmentView,
				OptionalInt.empty(),
				depthTextureView,
				OptionalDouble.empty()
			)
		) {
			RenderSystem.bindDefaultUniforms(renderPass);
			LightTexture lightTexture = minecraft.gameRenderer.lightTexture();
			if (lightTexture != null) {
				renderPass.bindSampler("Sampler2", lightTexture.getTextureView());
			}

			RenderSystem.AutoStorageIndexBuffer sequentialBuffer = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
			GpuBuffer indexBuffer = maxIndicesRequired == 0 ? null : sequentialBuffer.getBuffer(maxIndicesRequired);
			VertexFormat.IndexType indexType = maxIndicesRequired == 0 ? null : sequentialBuffer.type();
			for (ChunkSectionLayer layer : group.layers()) {
				List<RenderPass.Draw<GpuBufferSlice[]>> draws = drawsPerLayer.get(layer);
				if (draws == null || draws.isEmpty()) {
					continue;
				}

				RenderPipeline pipeline = switch (layer) {
					case SOLID -> BLOOM_TERRAIN_SOLID_PIPELINE;
					// 1.21.10 still splits cutout terrain into mipped and non-mipped layers.
					case CUTOUT, CUTOUT_MIPPED -> BLOOM_TERRAIN_CUTOUT_PIPELINE;
					default -> null;
				};
				if (pipeline == null) {
					continue;
				}

				renderPass.setPipeline(pipeline);
				renderPass.bindSampler("Sampler0", textureView);
				renderPass.drawMultipleIndexed(draws, indexBuffer, indexType, CHUNK_SECTION_UNIFORMS, dynamicTransforms);
			}
		}
	}

	public static int getInternalRenderWidth(int mainWidth) {
		return getScaledDimension(mainWidth);
	}

	public static int getInternalRenderHeight(int mainHeight) {
		return getScaledDimension(mainHeight);
	}

	public static void enableBloomDrawBuffers(RenderTarget target) {
		if (!preparedThisFrame || target == null || bloomAttachmentTexture == null) {
			return;
		}
		Integer framebufferId = attachBloomTexture(target);
		if (framebufferId == null) {
			return;
		}

		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
		GL20.glDrawBuffers(MRT_DRAW_BUFFERS);
	}

	public static void disableBloomDrawBuffers(RenderTarget target) {
		if (target == null) {
			return;
		}
		Integer framebufferId = getFramebufferId(target);
		if (framebufferId == null) {
			return;
		}

		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
		GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
	}

	private static boolean ensureAttachment(int width, int height) {
		if (!(RenderSystem.getDevice() instanceof GlDevice device)) {
			if (!loggedBackendFailure) {
				BloomMod.LOGGER.warn("Shine bloom attachment requires the OpenGL backend; bloom source capture is unavailable.");
				loggedBackendFailure = true;
			}
			return false;
		}

		if (bloomAttachmentTexture != null && bloomAttachmentTexture.getWidth(0) == width && bloomAttachmentTexture.getHeight(0) == height) {
			return true;
		}

		if (bloomAttachmentView != null) {
			bloomAttachmentView.close();
			bloomAttachmentView = null;
		}
		if (bloomAttachmentTexture != null) {
			bloomAttachmentTexture.close();
			bloomAttachmentTexture = null;
		}

		bloomAttachmentTexture = device.createTexture(
			() -> "Shine bloom attachment",
			GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
			TextureFormat.RGBA8,
			width,
			height,
			1,
			1
		);
		bloomAttachmentView = device.createTextureView(bloomAttachmentTexture);
		loggedAttachmentFailure = false;
		return true;
	}

	private static void clearAttachment() {
		if (bloomAttachmentTexture == null) {
			return;
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.clearColorTexture(bloomAttachmentTexture, 0);
	}

	private static int getScaledDimension(int size) {
		return Math.max(1, Math.round(size * INTERNAL_POST_SCALE));
	}

	private static Integer attachBloomTexture(RenderTarget target) {
		Integer framebufferId = getFramebufferId(target);
		if (framebufferId == null) {
			return null;
		}

		GlTexture texture = (GlTexture) bloomAttachmentTexture;
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, BLOOM_ATTACHMENT, GL11.GL_TEXTURE_2D, texture.glId(), 0);
		int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			if (!loggedAttachmentFailure) {
				BloomMod.LOGGER.warn("Shine bloom attachment framebuffer is incomplete: {}", Integer.toHexString(status));
				loggedAttachmentFailure = true;
			}
			GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
			return null;
		}

		return framebufferId;
	}

	private static Integer getFramebufferId(RenderTarget target) {
		if (!(RenderSystem.getDevice() instanceof GlDevice device)) {
			return null;
		}
		if (!(target.getColorTexture() instanceof GlTexture colorTexture)) {
			return null;
		}

		return colorTexture.getFbo(device.directStateAccess(), target.getDepthTexture());
	}

	private static RenderPipeline buildTerrainSourcePipeline(ResourceLocation location, boolean cutout) {
		RenderPipeline.Snippet fogSnippet = RenderPipeline.builder().withUniform("Fog", UniformType.UNIFORM_BUFFER).buildSnippet();
		RenderPipeline.Snippet genericBlocksSnippet = RenderPipeline.builder(fogSnippet)
			.withSampler("Sampler0")
			.withSampler("Sampler2")
			.withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS)
			.buildSnippet();
		RenderPipeline.Snippet bloomTerrainSnippet = RenderPipeline.builder(genericBlocksSnippet)
			.withUniform("Projection", UniformType.UNIFORM_BUFFER)
			.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
			.withVertexShader(TERRAIN_VERTEX_SHADER_ID)
			.withFragmentShader(BLOOM_TERRAIN_SOURCE_FRAGMENT_SHADER_ID)
			.buildSnippet();

		RenderPipeline.Builder builder = RenderPipeline.builder(bloomTerrainSnippet)
			.withLocation(location)
			.withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
			.withDepthWrite(false);
		if (cutout) {
			builder.withShaderDefine("ALPHA_CUTOUT", 0.5F);
		}
		return builder.build();
	}

	private static final class AttachedBloomRenderTarget extends RenderTarget {
		private AttachedBloomRenderTarget() {
			super("Shine Bloom Source", false);
		}

		private void setAttachment(RenderTarget mainTarget, GpuTexture texture, GpuTextureView textureView) {
			this.width = mainTarget.width;
			this.height = mainTarget.height;
			this.colorTexture = texture;
			this.colorTextureView = textureView;
			this.depthTexture = null;
			this.depthTextureView = null;
		}

		@Override
		public void resize(int width, int height) {
			this.width = width;
			this.height = height;
		}

		@Override
		public void destroyBuffers() {
		}

		@Override
		public void createBuffers(int width, int height) {
			this.width = width;
			this.height = height;
		}

		@Override
		public void copyDepthFrom(RenderTarget renderTarget) {
		}

		@Override
		public void blitToScreen() {
		}

		@Override
		public void blitAndBlendToTexture(GpuTextureView textureView) {
		}
	}
}

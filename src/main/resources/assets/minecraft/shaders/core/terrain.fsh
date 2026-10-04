#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in float bloomStrength;

layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 bloomColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
    if (bloomStrength <= 1.0e-5) {
        bloomColor = vec4(0.0);
    } else {
        float fogValue = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
        float fogAttenuation = 1.0 - fogValue;
        bloomColor = vec4(fragColor.rgb * fragColor.a * fogAttenuation, clamp(bloomStrength / 5.0, 0.0, 1.0));
    }
}

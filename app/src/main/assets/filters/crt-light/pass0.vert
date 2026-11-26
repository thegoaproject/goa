#version 300 es

struct UBO
{
    highp mat4 MVP;
};

uniform UBO global;

struct Push
{
    highp vec4 SourceSize;
    highp vec4 OriginalSize;
    highp vec4 OutputSize;
    highp uint FrameCount;
    highp float BLURSCALEX;
    highp float LOWLUMSCAN;
    highp float HILUMSCAN;
    highp float BRIGHTBOOST;
    highp float MASK_DARK;
    highp float MASK_FADE;
};

uniform Push params;

layout(location = 0) in vec4 Position;
out vec2 vTexCoord;
layout(location = 1) in vec2 TexCoord;
out float maskFade;
out vec2 invDims;

void main()
{
    gl_Position = global.MVP * Position;
    vTexCoord = TexCoord;
    maskFade = 0.33329999446868896484375 * params.MASK_FADE;
    invDims = vec2(1.0) / params.SourceSize.xy;
}


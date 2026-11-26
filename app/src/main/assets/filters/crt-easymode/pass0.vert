#version 300 es

struct UBO
{
    highp mat4 MVP;
    highp vec4 OutputSize;
    highp vec4 OriginalSize;
    highp vec4 SourceSize;
};

uniform UBO global;

struct Push
{
    highp float BRIGHT_BOOST;
    highp float DILATION;
    highp float GAMMA_INPUT;
    highp float GAMMA_OUTPUT;
    highp float MASK_SIZE;
    highp float MASK_STAGGER;
    highp float MASK_STRENGTH;
    highp float MASK_DOT_HEIGHT;
    highp float MASK_DOT_WIDTH;
    highp float SCANLINE_CUTOFF;
    highp float SCANLINE_BEAM_WIDTH_MAX;
    highp float SCANLINE_BEAM_WIDTH_MIN;
    highp float SCANLINE_BRIGHT_MAX;
    highp float SCANLINE_BRIGHT_MIN;
    highp float SCANLINE_STRENGTH;
    highp float SHARPNESS_H;
    highp float SHARPNESS_V;
};

uniform Push params;

layout(location = 0) in vec4 Position;
out vec2 vTexCoord;
layout(location = 1) in vec2 TexCoord;

void main()
{
    gl_Position = global.MVP * Position;
    vTexCoord = TexCoord;
}


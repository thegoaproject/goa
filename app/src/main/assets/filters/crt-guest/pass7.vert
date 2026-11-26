#version 300 es

struct UBO
{
    highp mat4 MVP;
    highp vec4 SourceSize;
    highp vec4 OriginalSize;
    highp vec4 OutputSize;
    highp uint FrameCount;
    highp float addnoise;
    highp float warpX;
    highp float warpY;
    highp float csize;
    highp float bsize1;
    highp float intres;
    highp float c_shape;
    highp float barspeed;
    highp float barintensity;
    highp float bardir;
    highp float sborder;
    highp float scan_falloff;
    highp float overscanX;
    highp float overscanY;
    highp float VShift;
    highp float bloom_dist;
    highp float bmask1;
    highp float hmask1;
    highp float interm;
};

uniform UBO global;

struct Push
{
    highp float brightboost;
    highp float brightboost1;
    highp float gsl;
    highp float scanline1;
    highp float scanline2;
    highp float beam_min;
    highp float beam_max;
    highp float beam_size;
    highp float glow;
    highp float maskstr;
    highp float inters;
    highp float bloom;
    highp float halation;
    highp float scans;
    highp float gamma_c;
    highp float gamma_c2;
    highp float gamma_out;
    highp float IOS;
    highp float no_scanlines;
    highp float tds;
    highp float clips;
    highp float hiscan;
};

uniform Push params;

layout(location = 0) in vec4 Position;
out vec2 vTexCoord;
layout(location = 1) in vec2 TexCoord;

void main()
{
    gl_Position = global.MVP * Position;
    vTexCoord = TexCoord * 1.000010013580322265625;
}


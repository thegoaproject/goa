#version 300 es

struct UBO
{
    highp mat4 MVP;
    highp vec4 SourceSize;
    highp vec4 OriginalSize;
    highp vec4 OutputSize;
    highp uint FrameCount;
    highp float bloom;
    highp float halation;
    highp float slotms;
    highp float mask_gamma;
    highp float gamma_out;
    highp float overscanX;
    highp float overscanY;
    highp float VShift;
    highp float intres;
    highp float prescalex;
    highp float c_shape;
    highp float barspeed;
    highp float bardir;
    highp float sborder;
    highp float bloom_dist;
    highp float deconr;
    highp float decons;
    highp float deconrr;
    highp float deconrg;
    highp float deconrb;
    highp float deconrry;
    highp float deconrgy;
    highp float deconrby;
    highp float dctypex;
    highp float dctypey;
    highp float post_br;
    highp float noisetype;
    highp float maskboost;
    highp float smoothmask;
    highp float gamma_c;
    highp float gamma_c2;
    highp float smask_mit;
    highp float mask_zoom;
    highp float no_scanlines;
    highp float bmask;
    highp float bmask1;
    highp float hmask1;
    highp float mzoom_sh;
    highp float mclip;
    highp float edgemask;
    highp float oimage;
    highp float interm;
};

uniform UBO global;

struct Push
{
    highp float IOS;
    highp float brightboost;
    highp float brightboost1;
    highp float csize;
    highp float bsize1;
    highp float warpX;
    highp float warpY;
    highp float glow;
    highp float shadowMask;
    highp float masksize;
    highp float hiscan;
    highp float slotmask;
    highp float slotmask1;
    highp float slotwidth;
    highp float double_slot;
    highp float mcut;
    highp float maskDark;
    highp float maskLight;
    highp float maskstr;
    highp float addnoised;
    highp float noiseresd;
    highp float barintensity;
    highp float mshift;
    highp float mask_layout;
    highp float mask_bloom;
    highp float pr_scan;
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


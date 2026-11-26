#version 300 es
precision mediump float;
precision highp int;

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
    highp float IOS;
    highp float h_sharp;
    highp float s_sharp;
    highp float spike;
    highp float ring;
};

uniform Push params;

uniform highp sampler2D LinearizePass;

in highp vec2 vTexCoord;
layout(location = 0) out highp vec4 FragColor;

void main()
{
    highp vec2 prescalex = vec2(textureSize(LinearizePass, 0)) / params.OriginalSize.xy;
    highp vec4 SourceSize = params.OriginalSize * vec4(prescalex.x, prescalex.y, 1.0 / prescalex.x, 1.0 / prescalex.y);
    highp float intera = texture(LinearizePass, vec2(0.75, 0.25)).w;
    bool interb = intera < 0.5;
    highp vec2 texcoord = vTexCoord;
    highp vec2 pos = texcoord;
    highp vec2 coffset = vec2(0.5);
    highp vec2 ps = SourceSize.zw;
    highp vec2 OGL2Pos = (pos * SourceSize.xy) - coffset;
    highp float fpx = fract(OGL2Pos.x);
    highp vec2 offx = vec2(ps.x, 0.0);
    highp vec2 x2 = offx * 2.0;
    highp vec2 pC4 = (floor(OGL2Pos) * ps) + (ps * 0.5);
    highp float zero = exp2(-params.h_sharp);
    highp float sharp1 = params.s_sharp * zero;
    highp float idiv = clamp(SourceSize.x / 400.0, 1.0, 2.0);
    highp float fdivider = max(min(prescalex.x, 2.0), idiv * float(interb));
    fdivider = 1.0 / max(fdivider, 1.0);
    highp float wl3 = (2.0 + fpx) * fdivider;
    highp float wl2 = (1.0 + fpx) * fdivider;
    highp float wl1 = fpx * fdivider;
    highp float wr1 = (1.0 - fpx) * fdivider;
    highp float wr2 = (2.0 - fpx) * fdivider;
    highp float wr3 = (3.0 - fpx) * fdivider;
    wl3 *= wl3;
    wl3 = exp2((-params.h_sharp) * wl3);
    wl2 *= wl2;
    wl2 = exp2((-params.h_sharp) * wl2);
    wl1 *= wl1;
    wl1 = exp2((-params.h_sharp) * wl1);
    wr1 *= wr1;
    wr1 = exp2((-params.h_sharp) * wr1);
    wr2 *= wr2;
    wr2 = exp2((-params.h_sharp) * wr2);
    wr3 *= wr3;
    wr3 = exp2((-params.h_sharp) * wr3);
    highp float fp1 = 1.0 - fpx;
    highp float twl3 = max(wl3 - sharp1, 0.0);
    highp float twl2 = max(wl2 - sharp1, mix(-0.119999997317790985107421875, 0.0, 1.0 - (fp1 * fp1)));
    highp float twl1 = max(wl1 - sharp1, -0.119999997317790985107421875);
    highp float twr1 = max(wr1 - sharp1, -0.119999997317790985107421875);
    highp float twr2 = max(wr2 - sharp1, mix(-0.119999997317790985107421875, 0.0, 1.0 - (fpx * fpx)));
    highp float twr3 = max(wr3 - sharp1, 0.0);
    bool sharp = sharp1 > 0.0;
    highp vec3 l3 = texture(LinearizePass, pC4 - x2).xyz;
    highp vec3 l2 = texture(LinearizePass, pC4 - offx).xyz;
    highp vec3 l1 = texture(LinearizePass, pC4).xyz;
    highp vec3 r1 = texture(LinearizePass, pC4 + offx).xyz;
    highp vec3 r2 = texture(LinearizePass, pC4 + x2).xyz;
    highp vec3 r3 = texture(LinearizePass, (pC4 + offx) + x2).xyz;
    highp vec3 colmin = min(min(l1, r1), min(l2, r2));
    highp vec3 colmax = max(max(l1, r1), max(l2, r2));
    highp vec3 color1 = ((((((l3 * twl3) + (l2 * twl2)) + (l1 * twl1)) + (r1 * twr1)) + (r2 * twr2)) + (r3 * twr3)) / vec3(((((twl3 + twl2) + twl1) + twr1) + twr2) + twr3);
    if (sharp)
    {
        color1 = clamp(mix(clamp(color1, colmin, colmax), color1, vec3(params.ring)), vec3(0.0), vec3(1.0));
    }
    highp float ts = 0.02500000037252902984619140625;
    highp vec3 luma = vec3(0.2125999927520751953125, 0.715200006961822509765625, 0.072200000286102294921875);
    highp float lm2 = max(max(l2.x, l2.y), l2.z);
    highp float lm1 = max(max(l1.x, l1.y), l1.z);
    highp float rm1 = max(max(r1.x, r1.y), r1.z);
    highp float rm2 = max(max(r2.x, r2.y), r2.z);
    highp float swl2 = max(twl2, 0.0) * (dot(l2, luma) + ts);
    highp float swl1 = max(twl1, 0.0) * (dot(l1, luma) + ts);
    highp float swr1 = max(twr1, 0.0) * (dot(r1, luma) + ts);
    highp float swr2 = max(twr2, 0.0) * (dot(r2, luma) + ts);
    highp float fscolor1 = ((((lm2 * swl2) + (lm1 * swl1)) + (rm1 * swr1)) + (rm2 * swr2)) / (((swl2 + swl1) + swr1) + swr2);
    highp float sresult = clamp(mix(max(max(color1.x, color1.y), color1.z), fscolor1, params.spike), 0.0, 1.0);
    FragColor = vec4(color1, sresult);
}


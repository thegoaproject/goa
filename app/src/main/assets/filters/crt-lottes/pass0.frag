#version 300 es
precision mediump float;
precision highp int;

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
    highp float hardScan;
    highp float hardPix;
    highp float warpX;
    highp float warpY;
    highp float maskDark;
    highp float maskLight;
    highp float scaleInLinearGamma;
    highp float shadowMask;
    highp float brightBoost;
    highp float hardBloomScan;
    highp float hardBloomPix;
    highp float bloomAmount;
    highp float shape;
};

uniform Push params;

uniform highp sampler2D Source;

in highp vec2 vTexCoord;
layout(location = 0) out highp vec4 FragColor;

highp vec2 Warp(inout highp vec2 pos)
{
    pos = (pos * 2.0) - vec2(1.0);
    pos *= vec2(1.0 + ((pos.y * pos.y) * params.warpX), 1.0 + ((pos.x * pos.x) * params.warpY));
    return (pos * 0.5) + vec2(0.5);
}

highp float ToLinear1(highp float c)
{
    if (params.scaleInLinearGamma == 0.0)
    {
        return c;
    }
    highp float _93;
    if (c <= 0.040449999272823333740234375)
    {
        _93 = c / 12.9200000762939453125;
    }
    else
    {
        _93 = pow((c + 0.054999999701976776123046875) / 1.05499994754791259765625, 2.400000095367431640625);
    }
    return _93;
}

highp vec3 ToLinear(highp vec3 c)
{
    if (params.scaleInLinearGamma == 0.0)
    {
        return c;
    }
    highp float param = c.x;
    highp float param_1 = c.y;
    highp float param_2 = c.z;
    return vec3(ToLinear1(param), ToLinear1(param_1), ToLinear1(param_2));
}

highp vec3 Fetch(inout highp vec2 pos, highp vec2 off)
{
    pos = (floor((pos * global.SourceSize.xy) + off) + vec2(0.5)) / global.SourceSize.xy;
    highp vec3 param = texture(Source, pos).xyz * params.brightBoost;
    return ToLinear(param);
}

highp vec2 Dist(inout highp vec2 pos)
{
    pos *= global.SourceSize.xy;
    return -((pos - floor(pos)) - vec2(0.5));
}

highp float Gaus(highp float pos, highp float scale)
{
    return exp2(scale * pow(abs(pos), params.shape));
}

highp vec3 Horz3(highp vec2 pos, highp float off)
{
    highp vec2 param = pos;
    highp vec2 param_1 = vec2(-1.0, off);
    highp vec3 _251 = Fetch(param, param_1);
    highp vec3 b = _251;
    highp vec2 param_2 = pos;
    highp vec2 param_3 = vec2(0.0, off);
    highp vec3 _258 = Fetch(param_2, param_3);
    highp vec3 c = _258;
    highp vec2 param_4 = pos;
    highp vec2 param_5 = vec2(1.0, off);
    highp vec3 _266 = Fetch(param_4, param_5);
    highp vec3 d = _266;
    highp vec2 param_6 = pos;
    highp vec2 _270 = Dist(param_6);
    highp float dst = _270.x;
    highp float scale = params.hardPix;
    highp float param_7 = dst - 1.0;
    highp float param_8 = scale;
    highp float wb = Gaus(param_7, param_8);
    highp float param_9 = dst + 0.0;
    highp float param_10 = scale;
    highp float wc = Gaus(param_9, param_10);
    highp float param_11 = dst + 1.0;
    highp float param_12 = scale;
    highp float wd = Gaus(param_11, param_12);
    return (((b * wb) + (c * wc)) + (d * wd)) / vec3((wb + wc) + wd);
}

highp vec3 Horz5(highp vec2 pos, highp float off)
{
    highp vec2 param = pos;
    highp vec2 param_1 = vec2(-2.0, off);
    highp vec3 _324 = Fetch(param, param_1);
    highp vec3 a = _324;
    highp vec2 param_2 = pos;
    highp vec2 param_3 = vec2(-1.0, off);
    highp vec3 _331 = Fetch(param_2, param_3);
    highp vec3 b = _331;
    highp vec2 param_4 = pos;
    highp vec2 param_5 = vec2(0.0, off);
    highp vec3 _338 = Fetch(param_4, param_5);
    highp vec3 c = _338;
    highp vec2 param_6 = pos;
    highp vec2 param_7 = vec2(1.0, off);
    highp vec3 _345 = Fetch(param_6, param_7);
    highp vec3 d = _345;
    highp vec2 param_8 = pos;
    highp vec2 param_9 = vec2(2.0, off);
    highp vec3 _353 = Fetch(param_8, param_9);
    highp vec3 e = _353;
    highp vec2 param_10 = pos;
    highp vec2 _357 = Dist(param_10);
    highp float dst = _357.x;
    highp float scale = params.hardPix;
    highp float param_11 = dst - 2.0;
    highp float param_12 = scale;
    highp float wa = Gaus(param_11, param_12);
    highp float param_13 = dst - 1.0;
    highp float param_14 = scale;
    highp float wb = Gaus(param_13, param_14);
    highp float param_15 = dst + 0.0;
    highp float param_16 = scale;
    highp float wc = Gaus(param_15, param_16);
    highp float param_17 = dst + 1.0;
    highp float param_18 = scale;
    highp float wd = Gaus(param_17, param_18);
    highp float param_19 = dst + 2.0;
    highp float param_20 = scale;
    highp float we = Gaus(param_19, param_20);
    return (((((a * wa) + (b * wb)) + (c * wc)) + (d * wd)) + (e * we)) / vec3((((wa + wb) + wc) + wd) + we);
}

highp float Scan(highp vec2 pos, highp float off)
{
    highp vec2 param = pos;
    highp vec2 _585 = Dist(param);
    highp float dst = _585.y;
    highp float param_1 = dst + off;
    highp float param_2 = params.hardScan;
    return Gaus(param_1, param_2);
}

highp vec3 Tri(highp vec2 pos)
{
    highp vec2 param = pos;
    highp float param_1 = -1.0;
    highp vec3 a = Horz3(param, param_1);
    highp vec2 param_2 = pos;
    highp float param_3 = 0.0;
    highp vec3 b = Horz5(param_2, param_3);
    highp vec2 param_4 = pos;
    highp float param_5 = 1.0;
    highp vec3 c = Horz3(param_4, param_5);
    highp vec2 param_6 = pos;
    highp float param_7 = -1.0;
    highp float wa = Scan(param_6, param_7);
    highp vec2 param_8 = pos;
    highp float param_9 = 0.0;
    highp float wb = Scan(param_8, param_9);
    highp vec2 param_10 = pos;
    highp float param_11 = 1.0;
    highp float wc = Scan(param_10, param_11);
    return ((a * wa) + (b * wb)) + (c * wc);
}

highp vec3 Horz7(highp vec2 pos, highp float off)
{
    highp vec2 param = pos;
    highp vec2 param_1 = vec2(-3.0, off);
    highp vec3 _436 = Fetch(param, param_1);
    highp vec3 a = _436;
    highp vec2 param_2 = pos;
    highp vec2 param_3 = vec2(-2.0, off);
    highp vec3 _443 = Fetch(param_2, param_3);
    highp vec3 b = _443;
    highp vec2 param_4 = pos;
    highp vec2 param_5 = vec2(-1.0, off);
    highp vec3 _450 = Fetch(param_4, param_5);
    highp vec3 c = _450;
    highp vec2 param_6 = pos;
    highp vec2 param_7 = vec2(0.0, off);
    highp vec3 _457 = Fetch(param_6, param_7);
    highp vec3 d = _457;
    highp vec2 param_8 = pos;
    highp vec2 param_9 = vec2(1.0, off);
    highp vec3 _464 = Fetch(param_8, param_9);
    highp vec3 e = _464;
    highp vec2 param_10 = pos;
    highp vec2 param_11 = vec2(2.0, off);
    highp vec3 _471 = Fetch(param_10, param_11);
    highp vec3 f = _471;
    highp vec2 param_12 = pos;
    highp vec2 param_13 = vec2(3.0, off);
    highp vec3 _479 = Fetch(param_12, param_13);
    highp vec3 g = _479;
    highp vec2 param_14 = pos;
    highp vec2 _483 = Dist(param_14);
    highp float dst = _483.x;
    highp float scale = params.hardBloomPix;
    highp float param_15 = dst - 3.0;
    highp float param_16 = scale;
    highp float wa = Gaus(param_15, param_16);
    highp float param_17 = dst - 2.0;
    highp float param_18 = scale;
    highp float wb = Gaus(param_17, param_18);
    highp float param_19 = dst - 1.0;
    highp float param_20 = scale;
    highp float wc = Gaus(param_19, param_20);
    highp float param_21 = dst + 0.0;
    highp float param_22 = scale;
    highp float wd = Gaus(param_21, param_22);
    highp float param_23 = dst + 1.0;
    highp float param_24 = scale;
    highp float we = Gaus(param_23, param_24);
    highp float param_25 = dst + 2.0;
    highp float param_26 = scale;
    highp float wf = Gaus(param_25, param_26);
    highp float param_27 = dst + 3.0;
    highp float param_28 = scale;
    highp float wg = Gaus(param_27, param_28);
    return (((((((a * wa) + (b * wb)) + (c * wc)) + (d * wd)) + (e * we)) + (f * wf)) + (g * wg)) / vec3((((((wa + wb) + wc) + wd) + we) + wf) + wg);
}

highp float BloomScan(highp vec2 pos, highp float off)
{
    highp vec2 param = pos;
    highp vec2 _601 = Dist(param);
    highp float dst = _601.y;
    highp float param_1 = dst + off;
    highp float param_2 = params.hardBloomScan;
    return Gaus(param_1, param_2);
}

highp vec3 Bloom(highp vec2 pos)
{
    highp vec2 param = pos;
    highp float param_1 = -2.0;
    highp vec3 a = Horz5(param, param_1);
    highp vec2 param_2 = pos;
    highp float param_3 = -1.0;
    highp vec3 b = Horz7(param_2, param_3);
    highp vec2 param_4 = pos;
    highp float param_5 = 0.0;
    highp vec3 c = Horz7(param_4, param_5);
    highp vec2 param_6 = pos;
    highp float param_7 = 1.0;
    highp vec3 d = Horz7(param_6, param_7);
    highp vec2 param_8 = pos;
    highp float param_9 = 2.0;
    highp vec3 e = Horz5(param_8, param_9);
    highp vec2 param_10 = pos;
    highp float param_11 = -2.0;
    highp float wa = BloomScan(param_10, param_11);
    highp vec2 param_12 = pos;
    highp float param_13 = -1.0;
    highp float wb = BloomScan(param_12, param_13);
    highp vec2 param_14 = pos;
    highp float param_15 = 0.0;
    highp float wc = BloomScan(param_14, param_15);
    highp vec2 param_16 = pos;
    highp float param_17 = 1.0;
    highp float wd = BloomScan(param_16, param_17);
    highp vec2 param_18 = pos;
    highp float param_19 = 2.0;
    highp float we = BloomScan(param_18, param_19);
    return ((((a * wa) + (b * wb)) + (c * wc)) + (d * wd)) + (e * we);
}

highp vec3 Mask(inout highp vec2 pos)
{
    highp vec3 mask = vec3(params.maskDark, params.maskDark, params.maskDark);
    if (params.shadowMask == 1.0)
    {
        highp float line = params.maskLight;
        highp float odd = 0.0;
        if (fract(pos.x * 0.16666667163372039794921875) < 0.5)
        {
            odd = 1.0;
        }
        if (fract((pos.y + odd) * 0.5) < 0.5)
        {
            line = params.maskDark;
        }
        pos.x = fract(pos.x * 0.3333333432674407958984375);
        if (pos.x < 0.333000004291534423828125)
        {
            mask.x = params.maskLight;
        }
        else
        {
            if (pos.x < 0.66600000858306884765625)
            {
                mask.y = params.maskLight;
            }
            else
            {
                mask.z = params.maskLight;
            }
        }
        mask *= line;
    }
    else
    {
        if (params.shadowMask == 2.0)
        {
            pos.x = fract(pos.x * 0.3333333432674407958984375);
            if (pos.x < 0.333000004291534423828125)
            {
                mask.x = params.maskLight;
            }
            else
            {
                if (pos.x < 0.66600000858306884765625)
                {
                    mask.y = params.maskLight;
                }
                else
                {
                    mask.z = params.maskLight;
                }
            }
        }
        else
        {
            if (params.shadowMask == 3.0)
            {
                pos.x += (pos.y * 3.0);
                pos.x = fract(pos.x * 0.16666667163372039794921875);
                if (pos.x < 0.333000004291534423828125)
                {
                    mask.x = params.maskLight;
                }
                else
                {
                    if (pos.x < 0.66600000858306884765625)
                    {
                        mask.y = params.maskLight;
                    }
                    else
                    {
                        mask.z = params.maskLight;
                    }
                }
            }
            else
            {
                if (params.shadowMask == 4.0)
                {
                    pos = floor(pos * vec2(1.0, 0.5));
                    pos.x += (pos.y * 3.0);
                    pos.x = fract(pos.x * 0.16666667163372039794921875);
                    if (pos.x < 0.333000004291534423828125)
                    {
                        mask.x = params.maskLight;
                    }
                    else
                    {
                        if (pos.x < 0.66600000858306884765625)
                        {
                            mask.y = params.maskLight;
                        }
                        else
                        {
                            mask.z = params.maskLight;
                        }
                    }
                }
            }
        }
    }
    return mask;
}

highp float ToSrgb1(highp float c)
{
    if (params.scaleInLinearGamma == 0.0)
    {
        return c;
    }
    highp float _146;
    if (c < 0.003130800090730190277099609375)
    {
        _146 = c * 12.9200000762939453125;
    }
    else
    {
        _146 = (1.05499994754791259765625 * pow(c, 0.416660010814666748046875)) - 0.054999999701976776123046875;
    }
    return _146;
}

highp vec3 ToSrgb(highp vec3 c)
{
    if (params.scaleInLinearGamma == 0.0)
    {
        return c;
    }
    highp float param = c.x;
    highp float param_1 = c.y;
    highp float param_2 = c.z;
    return vec3(ToSrgb1(param), ToSrgb1(param_1), ToSrgb1(param_2));
}

void main()
{
    highp vec2 param = vTexCoord;
    highp vec2 _954 = Warp(param);
    highp vec2 pos = _954;
    highp vec2 param_1 = pos;
    highp vec3 outColor = Tri(param_1);
    highp vec2 param_2 = pos;
    outColor += (Bloom(param_2) * params.bloomAmount);
    if (params.shadowMask > 0.0)
    {
        highp vec2 param_3 = (vTexCoord / global.OutputSize.zw) * 1.00000095367431640625;
        highp vec3 _981 = Mask(param_3);
        outColor *= _981;
    }
    highp vec3 param_4 = outColor;
    FragColor = vec4(ToSrgb(param_4), 1.0);
}


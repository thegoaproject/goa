#version 300 es
precision mediump float;
precision highp int;

const float _1763[15] = float[](2.0, 3.0, 3.0, 6.0, 6.0, 2.400000095367431640625, 3.400000095367431640625, 2.400000095367431640625, 3.25, 3.400000095367431640625, 4.400000095367431640625, 4.25, 7.400000095367431640625, 6.25, 5.25);

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

uniform highp sampler2D Source;
uniform highp sampler2D BloomPass;
uniform highp sampler2D LinearizePass;
uniform highp sampler2D PrePass;
uniform highp sampler2D StockPass;

in highp vec2 vTexCoord;
layout(location = 0) out highp vec4 FragColor;

highp vec2 Overscan(inout highp vec2 pos, highp float dx, highp float dy)
{
    pos = (pos * 2.0) - vec2(1.0);
    pos *= vec2(dx, dy);
    return (pos * 0.5) + vec2(0.5);
}

highp vec2 Warp(inout highp vec2 pos)
{
    pos = (pos * 2.0) - vec2(1.0);
    pos = mix(pos, vec2(pos.x * inversesqrt(1.0 - ((global.c_shape * pos.y) * pos.y)), pos.y * inversesqrt(1.0 - ((global.c_shape * pos.x) * pos.x))), vec2(params.warpX, params.warpY) / vec2(global.c_shape));
    return (pos * 0.5) + vec2(0.5);
}

void fetch_pixel(inout highp vec3 c, inout highp vec3 b, highp vec2 coord, highp vec2 bcoord)
{
    highp float stepx = global.OutputSize.z;
    highp float stepy = global.OutputSize.w;
    highp float ds = global.decons;
    highp vec2 dx = vec2(stepx, 0.0);
    highp vec2 dy = vec2(0.0, stepy);
    highp float posx = (2.0 * coord.x) - 1.0;
    highp float posy = (2.0 * coord.y) - 1.0;
    if (global.dctypex > 0.02500000037252902984619140625)
    {
        posx = sign(posx) * pow(abs(posx), 1.0499999523162841796875 - global.dctypex);
        dx *= posx;
    }
    if (global.dctypey > 0.02500000037252902984619140625)
    {
        posy = sign(posy) * pow(abs(posy), 1.0499999523162841796875 - global.dctypey);
        dy *= posy;
    }
    highp vec2 rc = (dx * global.deconrr) + (dy * global.deconrry);
    highp vec2 gc = (dx * global.deconrg) + (dy * global.deconrgy);
    highp vec2 bc = (dx * global.deconrb) + (dy * global.deconrby);
    highp float r1 = texture(Source, coord + rc).x;
    highp float g1 = texture(Source, coord + gc).y;
    highp float b1 = texture(Source, coord + bc).z;
    highp vec3 d = vec3(r1, g1, b1);
    c = clamp(mix(c, d, vec3(ds)), vec3(0.0), vec3(1.0));
    r1 = texture(BloomPass, bcoord + rc).x;
    g1 = texture(BloomPass, bcoord + gc).y;
    b1 = texture(BloomPass, bcoord + bc).z;
    d = vec3(r1, g1, b1);
    b = mix(b, d, vec3(min(ds, 1.0)));
}

highp float igc(highp float mc)
{
    return pow(mc, global.gamma_c);
}

highp vec3 Mask(inout highp vec2 pos, highp float mx, highp float mb)
{
    highp vec3 mask = vec3(params.maskDark, params.maskDark, params.maskDark);
    highp vec3 one = vec3(1.0);
    if (params.shadowMask == 0.0)
    {
        highp float mc = 1.0 - max(params.maskstr, 0.0);
        pos.x = fract(pos.x * 0.5);
        if (pos.x < 0.4900000095367431640625)
        {
            mask.x = 1.0;
            mask.y = mc;
            mask.z = 1.0;
        }
        else
        {
            mask.x = mc;
            mask.y = 1.0;
            mask.z = mc;
        }
    }
    else
    {
        if (params.shadowMask == 1.0)
        {
            highp float line = params.maskLight;
            highp float odd = 0.0;
            if (fract(pos.x / 6.0) < 0.4900000095367431640625)
            {
                odd = 1.0;
            }
            if (fract((pos.y + odd) / 2.0) < 0.4900000095367431640625)
            {
                line = params.maskDark;
            }
            pos.x = floor(mod(pos.x, 3.0));
            if (pos.x < 0.5)
            {
                mask.x = params.maskLight;
            }
            else
            {
                if (pos.x < 1.5)
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
                pos.x = floor(mod(pos.x, 3.0));
                if (pos.x < 0.5)
                {
                    mask.x = params.maskLight;
                }
                else
                {
                    if (pos.x < 1.5)
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
                    pos.x = fract(pos.x / 6.0);
                    if (pos.x < 0.300000011920928955078125)
                    {
                        mask.x = params.maskLight;
                    }
                    else
                    {
                        if (pos.x < 0.60000002384185791015625)
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
                        pos.x = fract(pos.x / 6.0);
                        if (pos.x < 0.300000011920928955078125)
                        {
                            mask.x = params.maskLight;
                        }
                        else
                        {
                            if (pos.x < 0.60000002384185791015625)
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
                        if (params.shadowMask == 5.0)
                        {
                            mask = vec3(0.0);
                            pos.x = fract(pos.x / 2.0);
                            if (pos.x < 0.4900000095367431640625)
                            {
                                mask.x = 1.0;
                                mask.z = 1.0;
                            }
                            else
                            {
                                mask.y = 1.0;
                            }
                            mask = clamp(mix(mix(one, mask, vec3(params.mcut)), mix(one, mask, vec3(params.maskstr)), vec3(mx)), vec3(0.0), vec3(1.0));
                        }
                        else
                        {
                            if (params.shadowMask == 6.0)
                            {
                                mask = vec3(0.0);
                                pos.x = floor(mod(pos.x, 3.0));
                                if (pos.x < 0.5)
                                {
                                    mask.x = 1.0;
                                }
                                else
                                {
                                    if (pos.x < 1.5)
                                    {
                                        mask.y = 1.0;
                                    }
                                    else
                                    {
                                        mask.z = 1.0;
                                    }
                                }
                                mask = clamp(mix(mix(one, mask, vec3(params.mcut)), mix(one, mask, vec3(params.maskstr)), vec3(mx)), vec3(0.0), vec3(1.0));
                            }
                            else
                            {
                                if (params.shadowMask == 7.0)
                                {
                                    mask = vec3(0.0);
                                    pos.x = fract(pos.x / 2.0);
                                    if (pos.x < 0.4900000095367431640625)
                                    {
                                        mask = vec3(0.0);
                                    }
                                    else
                                    {
                                        mask = vec3(1.0);
                                    }
                                    mask = clamp(mix(mix(one, mask, vec3(params.mcut)), mix(one, mask, vec3(params.maskstr)), vec3(mx)), vec3(0.0), vec3(1.0));
                                }
                                else
                                {
                                    if (params.shadowMask == 8.0)
                                    {
                                        mask = vec3(0.0);
                                        pos.x = fract(pos.x / 3.0);
                                        if (pos.x < 0.300000011920928955078125)
                                        {
                                            mask = vec3(0.0);
                                        }
                                        else
                                        {
                                            if (pos.x < 0.60000002384185791015625)
                                            {
                                                mask = vec3(1.0);
                                            }
                                            else
                                            {
                                                mask = vec3(1.0);
                                            }
                                        }
                                        mask = clamp(mix(mix(one, mask, vec3(params.mcut)), mix(one, mask, vec3(params.maskstr)), vec3(mx)), vec3(0.0), vec3(1.0));
                                    }
                                    else
                                    {
                                        if (params.shadowMask == 9.0)
                                        {
                                            mask = vec3(0.0);
                                            pos.x = fract(pos.x / 3.0);
                                            if (pos.x < 0.300000011920928955078125)
                                            {
                                                mask = vec3(0.0);
                                            }
                                            else
                                            {
                                                if (pos.x < 0.60000002384185791015625)
                                                {
                                                    mask.x = 1.0;
                                                    mask.z = 1.0;
                                                }
                                                else
                                                {
                                                    mask.y = 1.0;
                                                }
                                            }
                                            mask = clamp(mix(mix(one, mask, vec3(params.mcut)), mix(one, mask, vec3(params.maskstr)), vec3(mx)), vec3(0.0), vec3(1.0));
                                        }
                                        else
                                        {
                                            if (params.shadowMask == 10.0)
                                            {
                                                mask = vec3(0.0);
                                                pos.x = fract(pos.x * 0.25);
                                                if (pos.x < 0.20000000298023223876953125)
                                                {
                                                    mask = vec3(0.0);
                                                }
                                                else
                                                {
                                                    if (pos.x < 0.4000000059604644775390625)
                                                    {
                                                        mask.x = 1.0;
                                                    }
                                                    else
                                                    {
                                                        if (pos.x < 0.699999988079071044921875)
                                                        {
                                                            mask.y = 1.0;
                                                        }
                                                        else
                                                        {
                                                            mask.z = 1.0;
                                                        }
                                                    }
                                                }
                                                mask = clamp(mix(mix(one, mask, vec3(params.mcut)), mix(one, mask, vec3(params.maskstr)), vec3(mx)), vec3(0.0), vec3(1.0));
                                            }
                                            else
                                            {
                                                if (params.shadowMask == 11.0)
                                                {
                                                    mask = vec3(0.0);
                                                    pos.x = fract(pos.x * 0.25);
                                                    if (pos.x < 0.20000000298023223876953125)
                                                    {
                                                        mask.x = 1.0;
                                                    }
                                                    else
                                                    {
                                                        if (pos.x < 0.4000000059604644775390625)
                                                        {
                                                            mask.x = 1.0;
                                                            mask.y = 1.0;
                                                        }
                                                        else
                                                        {
                                                            if (pos.x < 0.699999988079071044921875)
                                                            {
                                                                mask.y = 1.0;
                                                                mask.z = 1.0;
                                                            }
                                                            else
                                                            {
                                                                mask.z = 1.0;
                                                            }
                                                        }
                                                    }
                                                    mask = clamp(mix(mix(one, mask, vec3(params.mcut)), mix(one, mask, vec3(params.maskstr)), vec3(mx)), vec3(0.0), vec3(1.0));
                                                }
                                                else
                                                {
                                                    if (params.shadowMask == 12.0)
                                                    {
                                                        mask = vec3(0.0);
                                                        pos.x = floor(mod(pos.x, 7.0));
                                                        if (pos.x < 0.5)
                                                        {
                                                            mask = vec3(0.0);
                                                        }
                                                        else
                                                        {
                                                            if (pos.x < 2.5)
                                                            {
                                                                mask.x = 1.0;
                                                            }
                                                            else
                                                            {
                                                                if (pos.x < 4.5)
                                                                {
                                                                    mask.y = 1.0;
                                                                }
                                                                else
                                                                {
                                                                    mask.z = 1.0;
                                                                }
                                                            }
                                                        }
                                                        mask = clamp(mix(mix(one, mask, vec3(params.mcut)), mix(one, mask, vec3(params.maskstr)), vec3(mx)), vec3(0.0), vec3(1.0));
                                                    }
                                                    else
                                                    {
                                                        if (params.shadowMask == 13.0)
                                                        {
                                                            mask = vec3(0.0);
                                                            pos.x = floor(mod(pos.x, 6.0));
                                                            if (pos.x < 0.5)
                                                            {
                                                                mask = vec3(0.0);
                                                            }
                                                            else
                                                            {
                                                                if (pos.x < 1.5)
                                                                {
                                                                    mask.x = 1.0;
                                                                }
                                                                else
                                                                {
                                                                    if (pos.x < 2.5)
                                                                    {
                                                                        mask.x = 1.0;
                                                                        mask.y = 1.0;
                                                                    }
                                                                    else
                                                                    {
                                                                        if (pos.x < 3.5)
                                                                        {
                                                                            mask = vec3(1.0);
                                                                        }
                                                                        else
                                                                        {
                                                                            if (pos.x < 4.5)
                                                                            {
                                                                                mask.y = 1.0;
                                                                                mask.z = 1.0;
                                                                            }
                                                                            else
                                                                            {
                                                                                mask.z = 1.0;
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                            mask = clamp(mix(mix(one, mask, vec3(params.mcut)), mix(one, mask, vec3(params.maskstr)), vec3(mx)), vec3(0.0), vec3(1.0));
                                                        }
                                                        else
                                                        {
                                                            mask = vec3(0.0);
                                                            pos.x = floor(mod(pos.x, 5.0));
                                                            if (pos.x < 0.5)
                                                            {
                                                                mask = vec3(0.0);
                                                            }
                                                            else
                                                            {
                                                                if (pos.x < 1.5)
                                                                {
                                                                    mask.x = 1.0;
                                                                }
                                                                else
                                                                {
                                                                    if (pos.x < 2.5)
                                                                    {
                                                                        mask.x = 1.0;
                                                                        mask.y = 1.0;
                                                                    }
                                                                    else
                                                                    {
                                                                        if (pos.x < 3.5)
                                                                        {
                                                                            mask.y = 1.0;
                                                                            mask.z = 1.0;
                                                                        }
                                                                        else
                                                                        {
                                                                            mask.z = 1.0;
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                            mask = clamp(mix(mix(one, mask, vec3(params.mcut)), mix(one, mask, vec3(params.maskstr)), vec3(mx)), vec3(0.0), vec3(1.0));
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (params.mask_layout > 0.5)
    {
        mask = mask.xzy;
    }
    highp float maskmin = min(min(mask.x, mask.y), mask.z);
    return ((mask - vec3(maskmin)) * (1.0 + ((global.maskboost - 1.0) * mb))) + vec3(maskmin);
}

highp float SlotMask(inout highp vec2 pos, highp float m, highp float swidth)
{
    if ((params.slotmask + params.slotmask1) == 0.0)
    {
        return 1.0;
    }
    else
    {
        pos.y = floor(pos.y / global.slotms);
        highp float mlen = swidth * 2.0;
        highp float px = floor(mod(pos.x, 0.999989986419677734375 * mlen));
        highp float py = floor((fract(pos.y / (2.0 * params.double_slot)) * 2.0) * params.double_slot);
        highp float slot_dark = mix(1.0 - params.slotmask1, 1.0 - params.slotmask, m);
        highp float slot = 1.0;
        if ((py == 0.0) && (px < swidth))
        {
            slot = slot_dark;
        }
        else
        {
            if ((py == params.double_slot) && (px >= swidth))
            {
                slot = slot_dark;
            }
        }
        return slot;
    }
}

highp vec3 plant(highp vec3 tar, highp float r)
{
    highp float t = max(max(tar.x, tar.y), tar.z) + 9.9999997473787516355514526367188e-06;
    return (tar * r) / vec3(t);
}

highp vec3 gc2(highp vec3 c, highp float w3)
{
    highp float mc = max(max(c.x, c.y), c.z);
    highp float gp = 1.0 / (1.0 + ((global.gamma_c2 - 1.0) * mix(0.375, 1.0, w3)));
    highp float mg = pow(mc, gp);
    return (c * mg) / vec3(mc + 1.0000000133514319600180897396058e-10);
}

highp vec3 _noise(inout highp vec3 v)
{
    if (params.addnoised < 0.0)
    {
        v.z = -params.addnoised;
    }
    else
    {
        v.z = mod(v.z, 6001.0) / 1753.0;
    }
    v = (fract(v) + fract(v * 10000.0)) + fract(v * 9.9999997473787516355514526367188e-05);
    v += vec3(0.123450003564357757568359375, 0.6789000034332275390625, 0.3141590058803558349609375);
    v = fract((v * dot(v, v)) * 123.45600128173828125);
    v = fract((v * dot(v, v)) * 123.45600128173828125);
    v = fract((v * dot(v, v)) * 123.45600128173828125);
    v = fract((v * dot(v, v)) * 123.45600128173828125);
    return v;
}

highp float humbar(inout highp float pos)
{
    if (params.barintensity == 0.0)
    {
        return 1.0;
    }
    else
    {
        highp float _1002;
        if (params.barintensity >= 0.0)
        {
            _1002 = pos;
        }
        else
        {
            _1002 = 1.0 - pos;
        }
        pos = _1002;
        pos = fract(pos + (mod(float(global.FrameCount), global.barspeed) / (global.barspeed - 1.0)));
        highp float _1028;
        if (params.barintensity < 0.0)
        {
            _1028 = pos;
        }
        else
        {
            _1028 = 1.0 - pos;
        }
        pos = _1028;
        return (1.0 - params.barintensity) + (params.barintensity * pos);
    }
}

highp float corner(inout highp vec2 pos)
{
    pos = abs((pos - vec2(0.5)) * 2.0);
    highp vec2 aspect = vec2(1.0, global.OutputSize.x / global.OutputSize.y);
    highp float b = (params.bsize1 * 0.0500000007450580596923828125) + 0.0005000000237487256526947021484375;
    pos.y += (b * (aspect.y - 1.0));
    highp vec2 crn = max(vec2(params.csize), vec2((2.0 * b) + 0.00150000001303851604461669921875));
    highp vec2 cp = max(pos - (vec2(1.0) - (crn * aspect)), vec2(0.0)) / aspect;
    highp float cd = sqrt(dot(cp, cp));
    pos = max(pos, (vec2(1.0) - crn) + vec2(cd));
    highp float res = mix(1.0, 0.0, smoothstep(1.0 - b, 1.0, sqrt(max(pos.x, pos.y))));
    return pow(res, global.sborder);
}

void main()
{
    highp vec4 SourceSize = global.OriginalSize;
    highp float gamma_in = 1.0 / texture(LinearizePass, vec2(0.25)).w;
    highp float intera = texture(LinearizePass, vec2(0.75, 0.25)).w;
    bool _1444 = intera < 0.3499999940395355224609375;
    bool _1451;
    if (_1444)
    {
        _1451 = global.interm == 5.0;
    }
    else
    {
        _1451 = _1444;
    }
    bool inter6 = _1451;
    bool _1457 = (intera < 0.3499999940395355224609375) && (!inter6);
    bool _1465;
    if (!_1457)
    {
        _1465 = global.no_scanlines > 0.02500000037252902984619140625;
    }
    else
    {
        _1465 = _1457;
    }
    bool _1472;
    if (_1465)
    {
        _1472 = params.hiscan < 0.5;
    }
    else
    {
        _1472 = _1465;
    }
    bool interb = _1472;
    highp vec2 texcoord = vTexCoord;
    if ((params.IOS > 0.0) && (!interb))
    {
        highp vec2 ofactor = global.OutputSize.xy / global.OriginalSize.xy;
        highp vec2 _1498;
        if (params.IOS < 2.5)
        {
            _1498 = floor(ofactor);
        }
        else
        {
            _1498 = ceil(ofactor);
        }
        highp vec2 intfactor = _1498;
        highp vec2 diff = ofactor / intfactor;
        highp float scan = diff.y;
        highp vec2 param = texcoord;
        highp float param_1 = scan;
        highp float param_2 = scan;
        highp vec2 _1520 = Overscan(param, param_1, param_2);
        texcoord = _1520;
        bool _1523 = params.IOS == 1.0;
        bool _1530;
        if (!_1523)
        {
            _1530 = params.IOS == 3.0;
        }
        else
        {
            _1530 = _1523;
        }
        if (_1530)
        {
            texcoord = vec2(vTexCoord.x, texcoord.y);
        }
    }
    texcoord.y -= (global.VShift * global.OriginalSize.w);
    highp vec2 param_3 = texcoord;
    highp float param_4 = (global.OriginalSize.x - global.overscanX) / global.OriginalSize.x;
    highp float param_5 = (global.OriginalSize.y - global.overscanY) / global.OriginalSize.y;
    highp vec2 _1568 = Overscan(param_3, param_4, param_5);
    texcoord = _1568;
    highp vec2 pos1 = vTexCoord;
    highp vec2 param_6 = texcoord;
    highp vec2 _1574 = Warp(param_6);
    highp vec2 pos = _1574;
    highp vec2 param_7 = vTexCoord;
    highp vec2 _1578 = Warp(param_7);
    highp vec2 pos0 = _1578;
    highp vec2 posb = (pos0 - vec2(0.5)) * 2.0;
    posb = max(abs(posb), abs((pos - vec2(0.5)) * 2.0)) * sign(posb);
    posb = (posb * 0.5) + vec2(0.5);
    highp vec3 color = texture(Source, pos1).xyz;
    highp vec3 Bloom = texture(BloomPass, pos).xyz;
    if ((((((abs(global.deconrr) + abs(global.deconrry)) + abs(global.deconrg)) + abs(global.deconrgy)) + abs(global.deconrb)) + abs(global.deconrby)) > 0.20000000298023223876953125)
    {
        highp vec3 param_8 = color;
        highp vec3 param_9 = Bloom;
        highp vec2 param_10 = pos1;
        highp vec2 param_11 = pos;
        fetch_pixel(param_8, param_9, param_10, param_11);
        color = param_8;
        Bloom = param_9;
    }
    highp float param_12 = max(max(color.x, color.y), color.z);
    highp float cm = igc(param_12);
    highp float mx1 = texture(Source, pos1).w;
    highp float colmx = max(mx1, cm);
    highp float w3 = min((max((cm - 0.0005000000237487256526947021484375) * 1.0004999637603759765625, 0.0) + 9.9999997473787516355514526367188e-05) / (colmx + 0.0005000000237487256526947021484375), 1.0);
    if (interb)
    {
        w3 = 1.0;
    }
    highp vec2 dx = vec2(0.001000000047497451305389404296875, 0.0);
    highp float mx0 = texture(Source, pos1 - dx).w;
    highp float mx2 = texture(Source, pos1 + dx).w;
    highp float mxg = max(max(mx0, mx1), max(mx2, cm));
    highp float mx = pow(mxg, 1.39999997615814208984375 / gamma_in);
    highp float cx = pow(colmx, 1.39999997615814208984375 / gamma_in);
    dx = vec2(global.OriginalSize.z, 0.0) * 0.25;
    mx0 = texture(Source, pos1 - dx).w;
    mx2 = texture(Source, pos1 + dx).w;
    highp float mb = 1.0 - min(abs(mx0 - mx2) / (0.5 + mx1), 1.0);
    highp vec3 one = vec3(1.0);
    highp vec3 orig1 = color;
    highp vec3 cmask = one;
    highp vec3 cmask1 = one;
    highp vec3 cmask2 = one;
    highp float mwidth = _1763[int(params.shadowMask)];
    highp float mask_compensate = fract(mwidth);
    highp float mwidth1 = mwidth;
    if (params.shadowMask > (-0.5))
    {
        highp vec2 maskcoord = vTexCoord * global.OutputSize.xy;
        highp vec2 scoord = maskcoord;
        mwidth = floor(mwidth) * params.masksize;
        highp float swidth = mwidth;
        bool zoomed = abs(global.mask_zoom) > 0.75;
        highp float mscale = 1.0;
        highp vec2 maskcoord0 = maskcoord;
        maskcoord.y = floor(maskcoord.y / params.masksize);
        mwidth1 = max(mwidth + global.mask_zoom, 2.0);
        if (params.mshift > 0.25)
        {
            highp float stagg_lvl = 1.0;
            if (fract(params.mshift) > 0.25)
            {
                stagg_lvl = 2.0;
            }
            highp float next_line = float(floor(mod(maskcoord.y, 2.0 * stagg_lvl)) < stagg_lvl);
            maskcoord0.x += ((next_line * 0.5) * mwidth1);
        }
        maskcoord = maskcoord0 / vec2(params.masksize);
        if (!zoomed)
        {
            highp vec2 param_13 = floor(maskcoord);
            highp float param_14 = mx;
            highp float param_15 = mb;
            highp vec3 _1864 = Mask(param_13, param_14, param_15);
            cmask = _1864;
        }
        else
        {
            mscale = mwidth1 / mwidth;
            highp float mlerp = fract(maskcoord.x / mscale);
            if (global.mzoom_sh > 0.02500000037252902984619140625)
            {
                mlerp = clamp(((1.0 + global.mzoom_sh) * mlerp) - (0.5 * global.mzoom_sh), 0.0, 1.0);
            }
            highp float mcoord = floor(maskcoord.x / mscale);
            bool _1899 = params.shadowMask == 12.0;
            bool _1906;
            if (_1899)
            {
                _1906 = global.mask_zoom == (-2.0);
            }
            else
            {
                _1906 = _1899;
            }
            if (_1906)
            {
                mcoord = ceil(maskcoord.x / mscale);
            }
            highp vec2 param_16 = vec2(mcoord, maskcoord.y);
            highp float param_17 = mx;
            highp float param_18 = mb;
            highp vec3 _1923 = Mask(param_16, param_17, param_18);
            highp vec2 param_19 = vec2(mcoord + 1.0, maskcoord.y);
            highp float param_20 = mx;
            highp float param_21 = mb;
            highp vec3 _1934 = Mask(param_19, param_20, param_21);
            cmask = mix(_1923, _1934, vec3(mlerp));
        }
        highp float sm_offset = 0.0;
        bool _1942 = params.shadowMask == 0.0;
        bool _1949;
        if (!_1942)
        {
            _1949 = params.shadowMask == 2.0;
        }
        else
        {
            _1949 = _1942;
        }
        bool _1956;
        if (!_1949)
        {
            _1956 = params.shadowMask == 5.0;
        }
        else
        {
            _1956 = _1949;
        }
        bool _1963;
        if (!_1956)
        {
            _1963 = params.shadowMask == 6.0;
        }
        else
        {
            _1963 = _1956;
        }
        bool _1970;
        if (!_1963)
        {
            _1970 = params.shadowMask == 8.0;
        }
        else
        {
            _1970 = _1963;
        }
        bool _1977;
        if (!_1970)
        {
            _1977 = params.shadowMask == 11.0;
        }
        else
        {
            _1977 = _1970;
        }
        bool bsm_offset = _1977;
        if (zoomed)
        {
            if ((params.mask_layout < 0.5) && bsm_offset)
            {
                sm_offset = 1.0;
            }
            else
            {
                if (bsm_offset)
                {
                    sm_offset = -1.0;
                }
            }
        }
        swidth = round(mwidth1);
        if (params.slotwidth > 0.5)
        {
            swidth = params.slotwidth;
        }
        highp float smask = 1.0;
        highp vec2 param_22 = scoord + vec2(sm_offset, 0.0);
        highp float param_23 = mx;
        highp float param_24 = swidth;
        highp float _2013 = SlotMask(param_22, param_23, param_24);
        smask = _2013;
        smask = clamp(smask + mix(global.smask_mit, 0.0, w3 * pow(colmx, 0.300000011920928955078125)), 0.0, 1.0);
        cmask2 = cmask;
        cmask *= smask;
        cmask1 = cmask;
        if (abs(params.mask_bloom) > 0.02500000037252902984619140625)
        {
            highp float maxbl = max(max(max(Bloom.x, Bloom.y), Bloom.z), mxg);
            maxbl *= max(mix(1.0, 2.0 - colmx, global.bloom_dist), 0.0);
            if (params.mask_bloom > 0.02500000037252902984619140625)
            {
                cmask = max(min(cmask + vec3(maxbl * params.mask_bloom), vec3(1.0)), cmask);
            }
            else
            {
                highp vec3 param_25 = pow(Bloom, vec3(0.3499999940395355224609375));
                highp float param_26 = maxbl;
                cmask = max(mix(cmask, (cmask * (1.0 - (0.5 * maxbl))) + plant(param_25, param_26), vec3(-params.mask_bloom)), cmask);
            }
        }
        color = pow(color, vec3(global.mask_gamma / gamma_in));
        color *= cmask;
        color = min(color, vec3(1.0));
        color = pow(color, vec3(gamma_in / global.mask_gamma));
        cmask = min(cmask, vec3(1.0));
        cmask1 = min(cmask1, vec3(1.0));
    }
    highp float dark_compensate = mix(max((clamp(mix(params.mcut, params.maskstr, mx), 0.0, 1.0) - 1.0) + mask_compensate, 0.0) + 1.0, 1.0, mx);
    if (params.shadowMask < (-0.5))
    {
        dark_compensate = 1.0;
    }
    highp float bb = mix(params.brightboost, params.brightboost1, mx) * dark_compensate;
    color *= bb;
    highp vec3 Glow = texture(BloomPass, pos).xyz;
    highp vec3 Ref = texture(LinearizePass, pos).xyz;
    highp float maxb = texture(BloomPass, pos).w;
    highp float vig = texture(PrePass, clamp(pos, vec2(0.0) + (global.OriginalSize.zw * 0.5), vec2(1.0) - (global.OriginalSize.zw * 0.5))).w;
    if (params.pr_scan > 0.02500000037252902984619140625)
    {
        highp float mbl = max(max(Bloom.x, Bloom.y), Bloom.z);
        highp vec3 param_27 = orig1;
        highp float param_28 = mbl;
        Bloom = mix(Bloom, mix(Bloom, plant(param_27, param_28), vec3(min(2.5 * (1.0 - w3), 1.0))), vec3(min(2.0 * params.pr_scan, 1.0)));
    }
    highp vec3 Bloom1 = Bloom;
    highp vec3 bcmask = mix(one, cmask1, vec3(global.bmask1));
    highp vec3 hcmask = mix(one, cmask1, vec3(global.hmask1));
    if (abs(global.bloom) > 0.02500000037252902984619140625)
    {
        if (global.bloom < (-0.00999999977648258209228515625))
        {
            highp vec3 param_29 = Bloom;
            highp float param_30 = maxb;
            Bloom1 = plant(param_29, param_30);
        }
        Bloom1 = min(Bloom1 * (orig1 + color), max(((vec3(colmx) + orig1) - color) * 0.5, Bloom1 * 0.001000000047497451305389404296875));
        Bloom1 = (Bloom1 + mix(Bloom1, mix(orig1 * colmx, Bloom1, vec3(0.5)), vec3(1.0) - color)) * 0.5;
        Bloom1 = (bcmask * Bloom1) * max(mix(1.0, 2.0 - colmx, global.bloom_dist), 0.0);
        color = pow(pow(color, vec3(global.mask_gamma / gamma_in)) + (pow(Bloom1, vec3(global.mask_gamma / gamma_in)) * abs(global.bloom)), vec3(gamma_in / global.mask_gamma));
    }
    if (global.halation > 0.00999999977648258209228515625)
    {
        Bloom = (Bloom + (Bloom * Bloom)) * 0.5;
        highp float mbl_1 = max(max(Bloom.x, Bloom.y), Bloom.z);
        highp float cmxh = 0.5 * (colmx + (colmx * colmx));
        mbl_1 = mix(mix(cmxh, mix(cmxh, mbl_1, mbl_1), colmx), mbl_1, mb);
        highp vec3 param_31 = Bloom;
        highp float param_32 = mix(sqrt(mbl_1 * cmxh), max(mbl_1 - (0.1500000059604644775390625 * (1.0 - colmx)), 0.4000000059604644775390625 * cmxh), pow(colmx, 0.25));
        Bloom = plant(param_31, param_32) * mix(0.425000011920928955078125, 1.0, colmx);
        highp vec3 param_33 = vec3(0.324999988079071044921875) + (orig1 / vec3(w3));
        highp float param_34 = 0.5 * (1.0 + w3);
        Bloom = (((vec3(3.0 - colmx) - color) * plant(param_33, param_34)) * hcmask) * Bloom;
        color = pow(pow(color, vec3(global.mask_gamma / gamma_in)) + (pow(Bloom, vec3(global.mask_gamma / gamma_in)) * global.halation), vec3(gamma_in / global.mask_gamma));
    }
    else
    {
        if (global.halation < (-0.00999999977648258209228515625))
        {
            highp float mbl_2 = max(max(Bloom.x, Bloom.y), Bloom.z);
            highp vec3 param_35 = ((Bloom + Ref) + orig1) + ((Bloom * Bloom) * Bloom);
            highp float param_36 = min(mbl_2 * mbl_2, 0.75);
            Bloom = plant(param_35, param_36);
            Bloom = (hcmask * (2.0 * mix(1.0, w3, 0.5 * colmx))) * Bloom;
            color -= (Bloom * global.halation);
        }
    }
    color = min(color, vec3(1.0));
    highp vec3 param_37 = color;
    highp float param_38 = w3;
    color = gc2(param_37, param_38);
    if (global.smoothmask > 0.125)
    {
        highp float w4 = pow(w3, 0.425000011920928955078125 + (0.300000011920928955078125 * global.smoothmask));
        w4 = max(w4 - ((0.17499999701976776123046875 * colmx) * global.smoothmask), 0.20000000298023223876953125);
        highp vec3 param_39 = orig1;
        highp float param_40 = 1.0 + ((0.17499999701976776123046875 * colmx) * global.smoothmask);
        color = mix(min(color / vec3(w4), plant(param_39, param_40)) * w4, color, vec3(w4));
    }
    Glow = mix(Glow, color * 0.25, vec3(colmx));
    if (params.glow >= 0.0)
    {
        color += ((Glow * 0.5) * params.glow);
    }
    else
    {
        cmask2 *= cmask2;
        color += ((min(cmask2, vec3(1.0)) * (-params.glow)) * Glow);
    }
    color = min(color, vec3(1.0));
    if (global.edgemask > 0.0500000007450580596923828125)
    {
        mx0 = texture(Source, pos1 - dx).w;
        mx0 = texture(Source, pos1 - (dx * (1.0 - (0.75 * sqrt(mx0))))).w;
        mx2 = texture(Source, pos1 + dx).w;
        mx2 = texture(Source, pos1 + (dx * (1.0 - (0.75 * sqrt(mx2))))).w;
        highp float mx3 = texture(Source, pos1 - (dx * 4.0)).w;
        highp float mx4 = texture(Source, pos1 + (dx * 4.0)).w;
        mx4 = max(pow(abs(mx3 - mx4), 0.550000011920928955078125 - (0.4000000059604644775390625 * cx)), min(max(mx3, mx4) / min(0.100000001490116119384765625 + cx, 1.0), 1.0));
        mb = 1.0 - abs(pow(mx0, 1.0 - (0.64999997615814208984375 * mx2)) - pow(mx2, 1.0 - (0.64999997615814208984375 * mx0)));
        mb = (mx4 * global.edgemask) * (1.00010001659393310546875 - (mb * mb));
        highp vec3 temp = mix(color, orig1, vec3(mb));
        color = max(temp + mix(mix(temp * 1.625, temp, vec3(cx)) * (3.5 * mb), vec3(0.0), pow(color, vec3(0.75) - vec3(0.5 * colmx))), color);
    }
    color *= mix(1.0, mix(0.5 * (1.0 + w3), w3, mx), params.pr_scan);
    color = min(color, max(orig1, color) * mix(one, cmask1, vec3(global.mclip)));
    color = pow(color, vec3(1.0 / global.gamma_out));
    highp float rc = (0.60000002384185791015625 * sqrt(max(max(color.x, color.y), color.z))) + 0.4000000059604644775390625;
    if (abs(params.addnoised) > 0.00999999977648258209228515625)
    {
        highp vec3 param_41 = vec3(floor((global.OutputSize.xy * vTexCoord) / vec2(params.noiseresd)), float(global.FrameCount));
        highp vec3 _2758 = _noise(param_41);
        highp vec3 noise0 = _2758;
        if (global.noisetype < 0.5)
        {
            color = mix(color, noise0, vec3((0.25 * abs(params.addnoised)) * rc));
        }
        else
        {
            color = min(color * mix(1.0, 1.5 * noise0.x, 0.5 * abs(params.addnoised)), vec3(1.0));
        }
    }
    colmx = max(max(orig1.x, orig1.y), orig1.z);
    color += (mix(cmask2, color * (0.125 * (1.0 - colmx)), vec3(min(20.0 * colmx, 1.0))) * global.bmask);
    highp float param_42 = mix(pos.y, pos.x, global.bardir);
    highp float _2827 = humbar(param_42);
    highp vec2 param_43 = posb;
    highp float _2835 = corner(param_43);
    FragColor = mix(vec4((((color * vig) * _2827) * global.post_br) * _2835, 1.0), texture(StockPass, (floor(pos1 * global.OriginalSize.xy) + vec2(0.5)) * global.OriginalSize.zw), vec4(step(pos1.x, global.oimage - 0.00025000001187436282634735107421875)));
}


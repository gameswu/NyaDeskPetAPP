/**
 * Live2DBridge.cpp — iOS Live2D C++ rendering engine.
 * Ported from live2d_native.cpp (Android JNI), replacing:
 *   - AAssetManager → POSIX fopen/fread
 *   - GLES2/gl2.h → OpenGLES/ES2/gl.h
 *   - __android_log_print → printf
 *   - JNI exports → L2DBridge_* C functions
 */

#include <string>
#include <vector>
#include <map>
#include <cstring>
#include <cstdlib>
#include <cmath>
#include <algorithm>
#include <cstdio>
#include <ctime>

#include <OpenGLES/ES2/gl.h>

#include "Live2DCubismCore.h"
#include "Live2DBridge.h"

// stb_image is compiled separately in stb_impl_ios.c
extern "C" {
    extern unsigned char* stbi_load_from_memory(const unsigned char*, int, int*, int*, int*, int);
    extern void stbi_image_free(void*);
    extern const char* stbi_failure_reason(void);
    extern void stbi_set_flip_vertically_on_load(int);
}

#define LOG_TAG "Live2D_iOS"
#define LOGI(...) do { printf("[" LOG_TAG "] "); printf(__VA_ARGS__); printf("\n"); } while(0)
#define LOGE(...) do { printf("[" LOG_TAG " ERROR] "); printf(__VA_ARGS__); printf("\n"); } while(0)

// ===================== Data Structures =====================

struct Live2DModel {
    csmMoc*   moc         = nullptr;
    csmModel* model       = nullptr;
    void*     mocBuffer   = nullptr;
    void*     modelBuffer = nullptr;

    std::vector<GLuint> textureIds;
    std::string         modelDir;

    std::map<std::string, int> parameterMap;

    float canvasWidth    = 0;
    float canvasHeight   = 0;
    float canvasOriginX  = 0;
    float canvasOriginY  = 0;
    float pixelsPerUnit  = 1;

    bool loaded = false;
};

struct ShaderInfo {
    GLuint program    = 0;
    GLint  a_position = -1;
    GLint  a_texCoord = -1;
    GLint  u_matrix   = -1;
    GLint  u_texture  = -1;
    GLint  u_opacity  = -1;
    GLint  u_multiplyColor = -1;
    GLint  u_screenColor   = -1;
};

// ===================== Globals =====================

static Live2DModel    g_model;
static ShaderInfo     g_shader;
static int   g_viewWidth  = 0;
static int   g_viewHeight = 0;
static float g_projMatrix[16];
static bool  g_initialized = false;

static float g_userScale   = 1.0f;
static float g_userOffsetX = 0.0f;
static float g_userOffsetY = 0.0f;

// ===================== Motion / Animation =====================

struct MotionKeyframe { float time; float value; };
struct MotionCurve    { std::string paramId; std::vector<MotionKeyframe> keyframes; };
struct MotionData     { float duration = 4.f; bool loop = true; float fadeInTime = 0.5f; float fadeOutTime = 0.5f; std::vector<MotionCurve> curves; };

static MotionData g_idleMotion;
static bool       g_hasIdleMotion = false;
static float      g_motionTime    = 0.f;
static double     g_lastTime      = 0.0;

static MotionData g_activeMotion;
static bool       g_hasActiveMotion = false;
static float      g_activeMotionTime = 0.f;
static int        g_activeMotionPriority = 0;

// Expression system
enum class ExprBlend { Add, Multiply, Overwrite };
struct ExprParam { std::string paramId; float value; ExprBlend blend; };
struct ExpressionData { std::string name; std::vector<ExprParam> params; };

static std::map<std::string, ExpressionData> g_expressions;
static std::string g_currentExpressionId;
static float       g_expressionFadeWeight = 0.f;
static float       g_expressionFadeSpeed  = 3.f;
static bool        g_expressionFadingIn   = false;

struct MotionEntry { std::string file; };
static std::map<std::string, std::vector<MotionEntry>> g_motionGroups;

static std::map<int, std::pair<float,float>> g_externalOverrides;

// ===================== Pose System =====================

struct PosePartInfo {
    std::string partId;
    int partIndex = -1;
    std::vector<std::string> linkIds;
    std::vector<int> linkIndices;
};

static std::vector<std::vector<PosePartInfo>> g_poseGroups;
static bool g_hasPose = false;
static const float POSE_FADE_SPEED = 5.0f;

// ===================== Physics =====================

struct PhysVec2 { float x = 0, y = 0; };

struct PhysInput {
    std::string sourceId;
    int sourceIdx = -1;
    float weight = 0;
    int type = 0;
    bool reflect = false;
};

struct PhysOutput {
    std::string destId;
    int destIdx = -1;
    int vertexIndex = 0;
    float scale = 1;
    float weight = 100;
    bool reflect = false;
};

struct PhysParticle {
    PhysVec2 position;
    PhysVec2 lastPosition;
    PhysVec2 velocity;
    PhysVec2 force;
    PhysVec2 lastGravity;
    float mobility = 1;
    float delay = 1;
    float acceleration = 1;
    float radius = 0;
};

struct PhysNorm {
    float posMin = -10, posDef = 0, posMax = 10;
    float angMin = -10, angDef = 0, angMax = 10;
};

struct PhysSubRig {
    std::vector<PhysInput> inputs;
    std::vector<PhysOutput> outputs;
    std::vector<PhysParticle> particles;
    PhysNorm norm;
};

struct PhysicsRig {
    std::vector<PhysSubRig> settings;
    PhysVec2 gravity = {0, -1};
    PhysVec2 wind = {0, 0};
    float fps = 60;
    bool loaded = false;
};

static PhysicsRig g_physics;

// ===================== Clipping Mask =====================

static GLuint g_maskFBO = 0;
static GLuint g_maskTexture = 0;
static int g_maskW = 0, g_maskH = 0;

struct MaskShaderInfo {
    GLuint program = 0;
    GLint a_position = -1;
    GLint a_texCoord = -1;
    GLint u_matrix = -1;
    GLint u_texture = -1;
    GLint u_opacity = -1;
};
static MaskShaderInfo g_maskShader;

struct MaskedShaderInfo {
    GLuint program = 0;
    GLint a_position = -1;
    GLint a_texCoord = -1;
    GLint u_matrix = -1;
    GLint u_texture = -1;
    GLint u_opacity = -1;
    GLint u_multiplyColor = -1;
    GLint u_screenColor = -1;
    GLint u_mask = -1;
    GLint u_viewportSize = -1;
};
static MaskedShaderInfo g_maskedShader;

// ===================== File I/O (POSIX) =====================

static std::vector<unsigned char> readFile(const std::string& path) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) { LOGE("Cannot open file: %s", path.c_str()); return {}; }
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<unsigned char> buf(sz);
    fread(buf.data(), 1, sz, f);
    fclose(f);
    return buf;
}

static std::string readFileString(const std::string& path) {
    auto d = readFile(path);
    return {d.begin(), d.end()};
}

static void* alignedMalloc(size_t size, size_t alignment) {
    void* p = nullptr;
    if (posix_memalign(&p, alignment, size) != 0) return nullptr;
    return p;
}

// ===================== Minimal JSON Helpers =====================

static size_t findKey(const std::string& j, const std::string& key, size_t s = 0) {
    std::string k = "\"" + key + "\"";
    size_t p = j.find(k, s);
    if (p == std::string::npos) return std::string::npos;
    p += k.size();
    while (p < j.size() && (j[p] == ' ' || j[p] == '\t' || j[p] == '\n' || j[p] == '\r' || j[p] == ':')) p++;
    return p;
}

static std::string extractString(const std::string& j, size_t p) {
    if (p >= j.size() || j[p] != '"') return "";
    size_t e = j.find('"', p + 1);
    return (e == std::string::npos) ? "" : j.substr(p + 1, e - p - 1);
}

static std::vector<std::string> extractStringArray(const std::string& j, size_t p) {
    std::vector<std::string> r;
    if (p >= j.size() || j[p] != '[') return r;
    p++;
    while (p < j.size()) {
        while (p < j.size() && (j[p] == ' ' || j[p] == '\t' || j[p] == '\n' || j[p] == '\r' || j[p] == ',')) p++;
        if (p >= j.size() || j[p] == ']') break;
        if (j[p] == '"') {
            size_t start = p;
            r.push_back(extractString(j, p));
            size_t close = j.find('"', start + 1);
            p = (close != std::string::npos) ? close + 1 : j.size();
        } else break;
    }
    return r;
}

struct ModelFileInfo { std::string mocPath; std::vector<std::string> texturePaths; };

static std::vector<std::string> extractObjectArray(const std::string& j, size_t p) {
    std::vector<std::string> r;
    if (p >= j.size() || j[p] != '[') return r;
    p++;
    while (p < j.size()) {
        while (p < j.size() && (j[p]==' '||j[p]=='\t'||j[p]=='\n'||j[p]=='\r'||j[p]==',')) p++;
        if (p >= j.size() || j[p] == ']') break;
        if (j[p] == '{') {
            int d = 0; size_t s = p;
            while (p < j.size()) {
                if (j[p] == '{') d++;
                else if (j[p] == '}') { d--; if (d == 0) { p++; break; } }
                p++;
            }
            r.push_back(j.substr(s, p - s));
        } else p++;
    }
    return r;
}

static size_t findArrayStart(const std::string& j, const std::string& key, size_t s = 0) {
    size_t p = findKey(j, key, s);
    if (p == std::string::npos) return std::string::npos;
    while (p < j.size() && j[p] != '[') p++;
    return (p < j.size()) ? p : std::string::npos;
}

// ===================== Pose3.json Parser & Runtime =====================

static void parsePose3Json(const std::string& json) {
    g_poseGroups.clear();
    g_hasPose = false;

    size_t groupsArr = findArrayStart(json, "Groups");
    if (groupsArr == std::string::npos) return;

    size_t p = groupsArr + 1;
    while (p < json.size()) {
        while (p < json.size() && (json[p]==' '||json[p]=='\t'||json[p]=='\n'||json[p]=='\r'||json[p]==',')) p++;
        if (p >= json.size() || json[p] == ']') break;

        if (json[p] == '[') {
            auto innerObjs = extractObjectArray(json, p);
            std::vector<PosePartInfo> group;
            for (const auto& obj : innerObjs) {
                PosePartInfo pi;
                size_t idPos = findKey(obj, "Id");
                if (idPos != std::string::npos) pi.partId = extractString(obj, idPos);
                size_t linkArr = findArrayStart(obj, "Link");
                if (linkArr != std::string::npos) pi.linkIds = extractStringArray(obj, linkArr);
                if (!pi.partId.empty()) group.push_back(pi);
            }
            if (group.size() >= 2) g_poseGroups.push_back(group);

            int d = 0;
            while (p < json.size()) {
                if (json[p] == '[') d++;
                else if (json[p] == ']') { d--; if (d == 0) { p++; break; } }
                p++;
            }
        } else {
            p++;
        }
    }
    g_hasPose = !g_poseGroups.empty();
    LOGI("Pose loaded: %d groups", (int)g_poseGroups.size());
}

static void initPosePartIndices() {
    int partCount = csmGetPartCount(g_model.model);
    const char** partIds = csmGetPartIds(g_model.model);
    std::map<std::string, int> partIdMap;
    for (int i = 0; i < partCount; i++) partIdMap[partIds[i]] = i;

    float* partOpacities = csmGetPartOpacities(g_model.model);

    for (auto& group : g_poseGroups) {
        for (size_t i = 0; i < group.size(); i++) {
            auto& pi = group[i];
            auto it = partIdMap.find(pi.partId);
            if (it != partIdMap.end()) {
                pi.partIndex = it->second;
                partOpacities[pi.partIndex] = (i == 0) ? 1.0f : 0.0f;
            }
            for (const auto& lid : pi.linkIds) {
                auto lit = partIdMap.find(lid);
                if (lit != partIdMap.end()) pi.linkIndices.push_back(lit->second);
            }
        }
    }
}

static void updatePose(float dt) {
    if (!g_hasPose || !g_model.loaded) return;
    float* partOpacities = csmGetPartOpacities(g_model.model);

    for (auto& group : g_poseGroups) {
        int dominantIdx = 0;
        float maxOpacity = 0.f;
        for (size_t i = 0; i < group.size(); i++) {
            if (group[i].partIndex < 0) continue;
            float op = partOpacities[group[i].partIndex];
            if (op > maxOpacity) { maxOpacity = op; dominantIdx = (int)i; }
        }
        for (size_t i = 0; i < group.size(); i++) {
            if (group[i].partIndex < 0) continue;
            float& opacity = partOpacities[group[i].partIndex];
            if ((int)i == dominantIdx) {
                opacity += dt * POSE_FADE_SPEED;
                if (opacity > 1.f) opacity = 1.f;
            } else {
                opacity -= dt * POSE_FADE_SPEED;
                if (opacity < 0.f) opacity = 0.f;
            }
            for (int linkIdx : group[i].linkIndices) partOpacities[linkIdx] = opacity;
        }
    }
}

static ModelFileInfo parseModel3Json(const std::string& json) {
    ModelFileInfo info;
    size_t fr = findKey(json, "FileReferences");
    if (fr == std::string::npos) return info;
    size_t mp = findKey(json, "Moc", fr);
    if (mp != std::string::npos) info.mocPath = extractString(json, mp);
    size_t tp = findKey(json, "Textures", fr);
    if (tp != std::string::npos) info.texturePaths = extractStringArray(json, tp);
    return info;
}

// ===================== Motion3.json Parser =====================

static MotionData parseMotion3Json(const std::string& json) {
    MotionData m;
    size_t dp = findKey(json, "Duration");
    if (dp != std::string::npos) m.duration = (float)strtod(json.c_str() + dp, nullptr);
    size_t lp = findKey(json, "Loop");
    if (lp != std::string::npos) m.loop = (json.substr(lp, 5).find("true") != std::string::npos);
    size_t fip = findKey(json, "FadeInTime");
    if (fip != std::string::npos) m.fadeInTime = (float)strtod(json.c_str() + fip, nullptr);
    size_t fop = findKey(json, "FadeOutTime");
    if (fop != std::string::npos) m.fadeOutTime = (float)strtod(json.c_str() + fop, nullptr);

    size_t cp = findKey(json, "Curves");
    if (cp == std::string::npos) return m;
    while (cp < json.size() && json[cp] != '[') cp++;
    if (cp >= json.size()) return m;

    int depth = 0;
    size_t pos = cp;
    while (pos < json.size()) {
        if (json[pos] == '[') depth++;
        else if (json[pos] == ']') { depth--; if (depth <= 0) break; }
        if (json[pos] == '{' && depth == 1) {
            int od = 0; size_t os = pos;
            while (pos < json.size()) {
                if (json[pos] == '{') od++;
                else if (json[pos] == '}') { od--; if (od == 0) { pos++; break; } }
                pos++;
            }
            std::string obj(json, os, pos - os);
            size_t tp2 = findKey(obj, "Target");
            if (tp2 == std::string::npos || extractString(obj, tp2) != "Parameter") continue;
            size_t ip = findKey(obj, "Id");
            if (ip == std::string::npos) continue;
            std::string paramId = extractString(obj, ip);
            size_t sp = findKey(obj, "Segments");
            if (sp == std::string::npos) continue;
            while (sp < obj.size() && obj[sp] != '[') sp++;
            if (sp >= obj.size()) continue;
            sp++;
            std::vector<float> nums;
            while (sp < obj.size() && obj[sp] != ']') {
                while (sp < obj.size() && (obj[sp]==' '||obj[sp]=='\t'||obj[sp]=='\n'||obj[sp]=='\r'||obj[sp]==',')) sp++;
                if (sp >= obj.size() || obj[sp] == ']') break;
                char* end;
                float v = (float)strtod(obj.c_str() + sp, &end);
                if (end > obj.c_str() + sp) { nums.push_back(v); sp = end - obj.c_str(); }
                else sp++;
            }
            MotionCurve curve;
            curve.paramId = paramId;
            if (nums.size() >= 2) {
                curve.keyframes.push_back({nums[0], nums[1]});
                size_t si = 2;
                while (si < nums.size()) {
                    int st = (int)nums[si];
                    if (st == 0 && si + 2 < nums.size()) {
                        curve.keyframes.push_back({nums[si+1], nums[si+2]}); si += 3;
                    } else if (st == 1 && si + 6 < nums.size()) {
                        curve.keyframes.push_back({nums[si+5], nums[si+6]}); si += 7;
                    } else if (si + 2 < nums.size()) {
                        curve.keyframes.push_back({nums[si+1], nums[si+2]}); si += 3;
                    } else break;
                }
            }
            if (!curve.keyframes.empty()) m.curves.push_back(curve);
        } else {
            pos++;
        }
    }
    LOGI("Motion parsed: dur=%.1f loop=%d curves=%d", m.duration, m.loop, (int)m.curves.size());
    return m;
}

static float evaluateMotionCurve(const MotionCurve& c, float t) {
    if (c.keyframes.empty()) return 0.f;
    if (t <= c.keyframes.front().time) return c.keyframes.front().value;
    if (t >= c.keyframes.back().time)  return c.keyframes.back().value;
    for (size_t i = 1; i < c.keyframes.size(); i++) {
        if (t <= c.keyframes[i].time) {
            float t0 = c.keyframes[i-1].time, t1 = c.keyframes[i].time;
            float v0 = c.keyframes[i-1].value, v1 = c.keyframes[i].value;
            float frac = (t1 > t0) ? (t - t0) / (t1 - t0) : 0.f;
            return v0 + (v1 - v0) * frac;
        }
    }
    return c.keyframes.back().value;
}

// ===================== Expression Parser =====================

static ExpressionData parseExp3Json(const std::string& json, const std::string& name) {
    ExpressionData expr;
    expr.name = name;
    size_t paramsArr = findArrayStart(json, "Parameters");
    if (paramsArr == std::string::npos) return expr;

    auto objs = extractObjectArray(json, paramsArr);
    for (const auto& obj : objs) {
        ExprParam ep;
        size_t ip = findKey(obj, "Id");
        if (ip != std::string::npos) ep.paramId = extractString(obj, ip);
        if (ep.paramId.empty()) continue;
        size_t vp = findKey(obj, "Value");
        if (vp != std::string::npos) ep.value = (float)strtod(obj.c_str() + vp, nullptr);
        size_t bp = findKey(obj, "Blend");
        if (bp != std::string::npos) {
            std::string blendStr = extractString(obj, bp);
            if (blendStr == "Multiply") ep.blend = ExprBlend::Multiply;
            else if (blendStr == "Overwrite") ep.blend = ExprBlend::Overwrite;
            else ep.blend = ExprBlend::Add;
        } else {
            ep.blend = ExprBlend::Add;
        }
        expr.params.push_back(ep);
    }
    LOGI("Expression parsed: %s (%d params)", name.c_str(), (int)expr.params.size());
    return expr;
}

// ===================== Physics3.json Parser =====================

static void parsePhysics3Json(const std::string& json) {
    g_physics = PhysicsRig();

    size_t fpsPos = findKey(json, "Fps");
    if (fpsPos != std::string::npos) g_physics.fps = (float)strtod(json.c_str() + fpsPos, nullptr);

    size_t efPos = findKey(json, "EffectiveForces");
    if (efPos != std::string::npos) {
        size_t gp = findKey(json, "Gravity", efPos);
        if (gp != std::string::npos) {
            size_t p = findKey(json, "X", gp);
            if (p != std::string::npos) g_physics.gravity.x = (float)strtod(json.c_str() + p, nullptr);
            p = findKey(json, "Y", gp);
            if (p != std::string::npos) g_physics.gravity.y = (float)strtod(json.c_str() + p, nullptr);
        }
        size_t wp = findKey(json, "Wind", efPos);
        if (wp != std::string::npos) {
            size_t p = findKey(json, "X", wp);
            if (p != std::string::npos) g_physics.wind.x = (float)strtod(json.c_str() + p, nullptr);
            p = findKey(json, "Y", wp);
            if (p != std::string::npos) g_physics.wind.y = (float)strtod(json.c_str() + p, nullptr);
        }
    }

    size_t psArr = findArrayStart(json, "PhysicsSettings");
    if (psArr == std::string::npos) return;
    auto settingObjs = extractObjectArray(json, psArr);

    for (const auto& sj : settingObjs) {
        PhysSubRig sub;

        size_t ia = findArrayStart(sj, "Input");
        if (ia != std::string::npos) {
            auto objs = extractObjectArray(sj, ia);
            for (const auto& ij : objs) {
                PhysInput inp;
                size_t sp = findKey(ij, "Source");
                if (sp != std::string::npos) {
                    size_t ip = findKey(ij, "Id", sp);
                    if (ip != std::string::npos) inp.sourceId = extractString(ij, ip);
                }
                size_t p = findKey(ij, "Weight");
                if (p != std::string::npos) inp.weight = (float)strtod(ij.c_str() + p, nullptr);
                p = findKey(ij, "Type");
                if (p != std::string::npos) inp.type = (extractString(ij, p) == "Angle") ? 1 : 0;
                p = findKey(ij, "Reflect");
                if (p != std::string::npos) inp.reflect = (ij.substr(p, 4) == "true");
                sub.inputs.push_back(inp);
            }
        }

        size_t oa = findArrayStart(sj, "Output");
        if (oa != std::string::npos) {
            auto objs = extractObjectArray(sj, oa);
            for (const auto& oj : objs) {
                PhysOutput out;
                size_t dp2 = findKey(oj, "Destination");
                if (dp2 != std::string::npos) {
                    size_t ip = findKey(oj, "Id", dp2);
                    if (ip != std::string::npos) out.destId = extractString(oj, ip);
                }
                size_t p = findKey(oj, "VertexIndex");
                if (p != std::string::npos) out.vertexIndex = (int)strtod(oj.c_str() + p, nullptr);
                p = findKey(oj, "Scale");
                if (p != std::string::npos) out.scale = (float)strtod(oj.c_str() + p, nullptr);
                p = findKey(oj, "Weight");
                if (p != std::string::npos) out.weight = (float)strtod(oj.c_str() + p, nullptr);
                p = findKey(oj, "Reflect");
                if (p != std::string::npos) out.reflect = (oj.substr(p, 4) == "true");
                sub.outputs.push_back(out);
            }
        }

        size_t va = findArrayStart(sj, "Vertices");
        if (va != std::string::npos) {
            auto objs = extractObjectArray(sj, va);
            for (const auto& vj : objs) {
                PhysParticle pp;
                size_t posP = findKey(vj, "Position");
                if (posP != std::string::npos) {
                    size_t p = findKey(vj, "X", posP);
                    if (p != std::string::npos) pp.position.x = (float)strtod(vj.c_str() + p, nullptr);
                    p = findKey(vj, "Y", posP);
                    if (p != std::string::npos) pp.position.y = (float)strtod(vj.c_str() + p, nullptr);
                }
                pp.lastPosition = pp.position;
                size_t p = findKey(vj, "Mobility");
                if (p != std::string::npos) pp.mobility = (float)strtod(vj.c_str() + p, nullptr);
                p = findKey(vj, "Delay");
                if (p != std::string::npos) pp.delay = (float)strtod(vj.c_str() + p, nullptr);
                p = findKey(vj, "Acceleration");
                if (p != std::string::npos) pp.acceleration = (float)strtod(vj.c_str() + p, nullptr);
                p = findKey(vj, "Radius");
                if (p != std::string::npos) pp.radius = (float)strtod(vj.c_str() + p, nullptr);
                sub.particles.push_back(pp);
            }
        }

        size_t np = findKey(sj, "Normalization");
        if (np != std::string::npos) {
            size_t posN = findKey(sj, "Position", np);
            if (posN != std::string::npos) {
                size_t p = findKey(sj, "Minimum", posN);
                if (p != std::string::npos) sub.norm.posMin = (float)strtod(sj.c_str() + p, nullptr);
                p = findKey(sj, "Default", posN);
                if (p != std::string::npos) sub.norm.posDef = (float)strtod(sj.c_str() + p, nullptr);
                p = findKey(sj, "Maximum", posN);
                if (p != std::string::npos) sub.norm.posMax = (float)strtod(sj.c_str() + p, nullptr);
            }
            size_t angN = findKey(sj, "Angle", np);
            if (angN != std::string::npos) {
                size_t p = findKey(sj, "Minimum", angN);
                if (p != std::string::npos) sub.norm.angMin = (float)strtod(sj.c_str() + p, nullptr);
                p = findKey(sj, "Default", angN);
                if (p != std::string::npos) sub.norm.angDef = (float)strtod(sj.c_str() + p, nullptr);
                p = findKey(sj, "Maximum", angN);
                if (p != std::string::npos) sub.norm.angMax = (float)strtod(sj.c_str() + p, nullptr);
            }
        }

        g_physics.settings.push_back(sub);
    }
    LOGI("Physics parsed: %d settings, gravity=(%.1f,%.1f), fps=%.0f",
         (int)g_physics.settings.size(), g_physics.gravity.x, g_physics.gravity.y, g_physics.fps);
    g_physics.loaded = true;
}

// ===================== Physics Simulation =====================

static float directionToRadian(PhysVec2 from, PhysVec2 to) {
    float q1 = atan2f(from.y, from.x);
    float q2 = atan2f(to.y, to.x);
    float r = q2 - q1;
    while (r < -(float)M_PI) r += 2.f * (float)M_PI;
    while (r >  (float)M_PI) r -= 2.f * (float)M_PI;
    return r;
}

static float normalizePhysInput(float val, float pMin, float pMax, float pDef,
                                float nMin, float nDef, float nMax) {
    float diff = val - pDef;
    if (diff > 0.0001f) {
        float pr = pMax - pDef, nr = nMax - nDef;
        return (pr > 0.0001f) ? nDef + diff / pr * nr : nMax;
    } else if (diff < -0.0001f) {
        float pr = pDef - pMin, nr = nDef - nMin;
        return (pr > 0.0001f) ? nDef + diff / pr * nr : nMin;
    }
    return nDef;
}

static void initPhysics() {
    if (!g_physics.loaded || !g_model.loaded) return;
    for (auto& sub : g_physics.settings) {
        for (auto& inp : sub.inputs) {
            auto it = g_model.parameterMap.find(inp.sourceId);
            inp.sourceIdx = (it != g_model.parameterMap.end()) ? it->second : -1;
        }
        for (auto& out : sub.outputs) {
            auto it = g_model.parameterMap.find(out.destId);
            out.destIdx = (it != g_model.parameterMap.end()) ? it->second : -1;
        }
        if (!sub.particles.empty()) {
            sub.particles[0].position = {0, 0};
            sub.particles[0].lastPosition = {0, 0};
            sub.particles[0].lastGravity = {0, 1};
            for (size_t i = 1; i < sub.particles.size(); i++) {
                sub.particles[i].position.x = 0;
                sub.particles[i].position.y = sub.particles[i-1].position.y + sub.particles[i].radius;
                sub.particles[i].lastPosition = sub.particles[i].position;
                sub.particles[i].velocity = {0, 0};
                sub.particles[i].force = {0, 0};
                sub.particles[i].lastGravity = {0, 1};
            }
        }
    }
    LOGI("Physics initialized: %d settings", (int)g_physics.settings.size());
}

static void updatePhysics(float dt) {
    if (!g_physics.loaded || !g_model.loaded) return;

    float* pv = csmGetParameterValues(g_model.model);
    const float* pd = csmGetParameterDefaultValues(g_model.model);
    const float* pmn = csmGetParameterMinimumValues(g_model.model);
    const float* pmx = csmGetParameterMaximumValues(g_model.model);
    int pc = csmGetParameterCount(g_model.model);
    const float AIR_RES = 5.0f;

    for (auto& sub : g_physics.settings) {
        float totalAngle = 0, totalTx = 0;
        for (const auto& inp : sub.inputs) {
            if (inp.sourceIdx < 0 || inp.sourceIdx >= pc) continue;
            float w = inp.weight / 100.0f;
            float normalized;
            if (inp.type == 1) {
                normalized = normalizePhysInput(pv[inp.sourceIdx], pmn[inp.sourceIdx], pmx[inp.sourceIdx],
                                                pd[inp.sourceIdx], sub.norm.angMin, sub.norm.angDef, sub.norm.angMax);
            } else {
                normalized = normalizePhysInput(pv[inp.sourceIdx], pmn[inp.sourceIdx], pmx[inp.sourceIdx],
                                                pd[inp.sourceIdx], sub.norm.posMin, sub.norm.posDef, sub.norm.posMax);
            }
            if (inp.reflect) normalized = -normalized;
            if (inp.type == 1) totalAngle += normalized * w;
            else               totalTx += normalized * w;
        }

        if (sub.particles.empty()) continue;

        sub.particles[0].position.x = totalTx;

        float totalRad = totalAngle * (float)M_PI / 180.0f;
        PhysVec2 curGrav = { sinf(totalRad), cosf(totalRad) };

        for (size_t i = 1; i < sub.particles.size(); i++) {
            auto& p = sub.particles[i];
            auto& prev = sub.particles[i-1];

            p.force.x = curGrav.x * p.acceleration + g_physics.wind.x;
            p.force.y = curGrav.y * p.acceleration + g_physics.wind.y;
            PhysVec2 saved = p.position;
            float delay = p.delay * dt * 30.0f;

            PhysVec2 dir = { p.position.x - prev.position.x, p.position.y - prev.position.y };

            float rad = directionToRadian(p.lastGravity, curGrav) / AIR_RES;
            float cr = cosf(rad), sr = sinf(rad);
            float rx = cr * dir.x - sr * dir.y;
            float ry = sr * dir.x + cr * dir.y;
            dir.x = rx; dir.y = ry;

            p.position.x = prev.position.x + dir.x;
            p.position.y = prev.position.y + dir.y;

            p.position.x += p.velocity.x * delay + p.force.x * delay * delay;
            p.position.y += p.velocity.y * delay + p.force.y * delay * delay;

            float dx = p.position.x - prev.position.x;
            float dy = p.position.y - prev.position.y;
            float dist = sqrtf(dx * dx + dy * dy);
            if (dist > 0.0001f) {
                p.position.x = prev.position.x + (dx / dist) * p.radius;
                p.position.y = prev.position.y + (dy / dist) * p.radius;
            }
            if (fabsf(p.position.x) < 0.001f) p.position.x = 0.f;

            if (delay > 0.0001f) {
                p.velocity.x = (p.position.x - saved.x) / delay * p.mobility;
                p.velocity.y = (p.position.y - saved.y) / delay * p.mobility;
            }
            p.lastGravity = curGrav;
        }

        for (const auto& out : sub.outputs) {
            if (out.destIdx < 0 || out.destIdx >= pc) continue;
            int vi = out.vertexIndex;
            if (vi < 1 || vi >= (int)sub.particles.size()) continue;

            PhysVec2 parentDir;
            if (vi >= 2) {
                parentDir.x = sub.particles[vi-1].position.x - sub.particles[vi-2].position.x;
                parentDir.y = sub.particles[vi-1].position.y - sub.particles[vi-2].position.y;
            } else {
                parentDir = {0, 1};
            }
            PhysVec2 curDir = {
                sub.particles[vi].position.x - sub.particles[vi-1].position.x,
                sub.particles[vi].position.y - sub.particles[vi-1].position.y
            };
            float angle = directionToRadian(parentDir, curDir);
            if (out.reflect) angle = -angle;

            float outputValue = angle * out.scale;
            float w = out.weight / 100.0f;
            float blended = pv[out.destIdx] * (1.f - w) + outputValue * w;
            pv[out.destIdx] = std::clamp(blended, pmn[out.destIdx], pmx[out.destIdx]);
        }
    }
}

static double getCurrentTime() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

// ===================== Shaders =====================

static const char* kVS =
    "attribute vec4 a_position;\n"
    "attribute vec2 a_texCoord;\n"
    "varying vec2 v_texCoord;\n"
    "uniform mat4 u_matrix;\n"
    "void main() {\n"
    "    gl_Position = u_matrix * a_position;\n"
    "    v_texCoord = a_texCoord;\n"
    "}\n";

static const char* kFS =
    "precision mediump float;\n"
    "varying vec2 v_texCoord;\n"
    "uniform sampler2D u_texture;\n"
    "uniform float u_opacity;\n"
    "uniform vec4 u_multiplyColor;\n"
    "uniform vec4 u_screenColor;\n"
    "void main() {\n"
    "    vec4 c = texture2D(u_texture, v_texCoord);\n"
    "    c.rgb *= c.a;\n"
    "    c.rgb *= u_multiplyColor.rgb;\n"
    "    c.rgb = clamp(c.rgb + u_screenColor.rgb * c.a - c.rgb * u_screenColor.rgb, 0.0, 1.0);\n"
    "    gl_FragColor = c * u_opacity;\n"
    "}\n";

static GLuint compileShader(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char buf[512]; glGetShaderInfoLog(s, 512, nullptr, buf); LOGE("Shader err: %s", buf); glDeleteShader(s); return 0; }
    return s;
}

static void initShaders() {
    GLuint vs = compileShader(GL_VERTEX_SHADER, kVS);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFS);
    if (!vs || !fs) return;
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs); glAttachShader(prog, fs);
    glLinkProgram(prog);
    GLint ok; glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) { char buf[512]; glGetProgramInfoLog(prog, 512, nullptr, buf); LOGE("Link err: %s", buf); return; }
    glDeleteShader(vs); glDeleteShader(fs);
    g_shader.program    = prog;
    g_shader.a_position = glGetAttribLocation(prog, "a_position");
    g_shader.a_texCoord = glGetAttribLocation(prog, "a_texCoord");
    g_shader.u_matrix   = glGetUniformLocation(prog, "u_matrix");
    g_shader.u_texture  = glGetUniformLocation(prog, "u_texture");
    g_shader.u_opacity  = glGetUniformLocation(prog, "u_opacity");
    g_shader.u_multiplyColor = glGetUniformLocation(prog, "u_multiplyColor");
    g_shader.u_screenColor   = glGetUniformLocation(prog, "u_screenColor");
    LOGI("Shaders OK, program=%d", prog);
}

// ===================== Mask / Masked Shaders =====================

// Mask fragment shader: renders drawable alpha to FBO for clipping
static const char* kMaskFS =
    "precision mediump float;\n"
    "varying vec2 v_texCoord;\n"
    "uniform sampler2D u_texture;\n"
    "uniform float u_opacity;\n"
    "void main() {\n"
    "    float a = texture2D(u_texture, v_texCoord).a * u_opacity;\n"
    "    gl_FragColor = vec4(a, a, a, a);\n"
    "}\n";

// Masked fragment shader: samples mask texture via screen-space UV
// Uses gl_FragCoord.xy / u_viewportSize to compute mask UV.
// Both mask FBO and screen FBO use the same viewport dimensions and
// the same projection matrix, so gl_FragCoord maps 1:1.
static const char* kMaskedFS =
    "precision mediump float;\n"
    "varying vec2 v_texCoord;\n"
    "uniform sampler2D u_texture;\n"
    "uniform sampler2D u_mask;\n"
    "uniform float u_opacity;\n"
    "uniform vec4 u_multiplyColor;\n"
    "uniform vec4 u_screenColor;\n"
    "uniform vec2 u_viewportSize;\n"
    "void main() {\n"
    "    vec4 c = texture2D(u_texture, v_texCoord);\n"
    "    c.rgb *= c.a;\n"
    "    vec2 maskUV = gl_FragCoord.xy / u_viewportSize;\n"
    "    float maskVal = texture2D(u_mask, maskUV).a;\n"
    "    c *= maskVal;\n"
    "    c.rgb *= u_multiplyColor.rgb;\n"
    "    c.rgb = clamp(c.rgb + u_screenColor.rgb * c.a - c.rgb * u_screenColor.rgb, 0.0, 1.0);\n"
    "    gl_FragColor = c * u_opacity;\n"
    "}\n";

static void initMaskShaders() {
    {
        GLuint vs = compileShader(GL_VERTEX_SHADER, kVS);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, kMaskFS);
        if (!vs || !fs) return;
        GLuint prog = glCreateProgram();
        glAttachShader(prog, vs); glAttachShader(prog, fs);
        glLinkProgram(prog);
        GLint ok; glGetProgramiv(prog, GL_LINK_STATUS, &ok);
        if (!ok) { char buf[512]; glGetProgramInfoLog(prog, 512, nullptr, buf); LOGE("Mask link err: %s", buf); return; }
        glDeleteShader(vs); glDeleteShader(fs);
        g_maskShader.program    = prog;
        g_maskShader.a_position = glGetAttribLocation(prog, "a_position");
        g_maskShader.a_texCoord = glGetAttribLocation(prog, "a_texCoord");
        g_maskShader.u_matrix   = glGetUniformLocation(prog, "u_matrix");
        g_maskShader.u_texture  = glGetUniformLocation(prog, "u_texture");
        g_maskShader.u_opacity  = glGetUniformLocation(prog, "u_opacity");
        LOGI("Mask shader OK, program=%d", prog);
    }
    {
        GLuint vs = compileShader(GL_VERTEX_SHADER, kVS);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, kMaskedFS);
        if (!vs || !fs) return;
        GLuint prog = glCreateProgram();
        glAttachShader(prog, vs); glAttachShader(prog, fs);
        glLinkProgram(prog);
        GLint ok; glGetProgramiv(prog, GL_LINK_STATUS, &ok);
        if (!ok) { char buf[512]; glGetProgramInfoLog(prog, 512, nullptr, buf); LOGE("Masked link err: %s", buf); return; }
        glDeleteShader(vs); glDeleteShader(fs);
        g_maskedShader.program       = prog;
        g_maskedShader.a_position    = glGetAttribLocation(prog, "a_position");
        g_maskedShader.a_texCoord    = glGetAttribLocation(prog, "a_texCoord");
        g_maskedShader.u_matrix      = glGetUniformLocation(prog, "u_matrix");
        g_maskedShader.u_texture     = glGetUniformLocation(prog, "u_texture");
        g_maskedShader.u_opacity     = glGetUniformLocation(prog, "u_opacity");
        g_maskedShader.u_multiplyColor = glGetUniformLocation(prog, "u_multiplyColor");
        g_maskedShader.u_screenColor   = glGetUniformLocation(prog, "u_screenColor");
        g_maskedShader.u_mask          = glGetUniformLocation(prog, "u_mask");
        g_maskedShader.u_viewportSize  = glGetUniformLocation(prog, "u_viewportSize");
        LOGI("Masked shader OK, program=%d", prog);
    }
}

static void ensureMaskFBO(int w, int h) {
    if (g_maskW == w && g_maskH == h && g_maskFBO != 0) return;

    // Save current FBO to restore after setup
    GLint prevFBO = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFBO);

    if (g_maskFBO) { glDeleteFramebuffers(1, &g_maskFBO); g_maskFBO = 0; }
    if (g_maskTexture) { glDeleteTextures(1, &g_maskTexture); g_maskTexture = 0; }
    g_maskW = w; g_maskH = h;

    glGenTextures(1, &g_maskTexture);
    glBindTexture(GL_TEXTURE_2D, g_maskTexture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenFramebuffers(1, &g_maskFBO);
    glBindFramebuffer(GL_FRAMEBUFFER, g_maskFBO);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_maskTexture, 0);

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) LOGE("Mask FBO incomplete: 0x%x", status);
    else LOGI("Mask FBO created: %dx%d", w, h);

    glBindFramebuffer(GL_FRAMEBUFFER, prevFBO);
}

// ===================== Projection =====================

static void identity(float* m) { memset(m, 0, 64); m[0]=m[5]=m[10]=m[15]=1; }

static void updateProjection() {
    identity(g_projMatrix);
    if (!g_model.loaded || g_viewWidth == 0 || g_viewHeight == 0) return;

    float mw = g_model.canvasWidth  / g_model.pixelsPerUnit;
    float mh = g_model.canvasHeight / g_model.pixelsPerUnit;
    float ma = mw / mh;
    float va = (float)g_viewWidth / g_viewHeight;

    float sx, sy;
    if (va > ma) {
        sy = 2.f / mh;
        sx = sy * ((float)g_viewHeight / g_viewWidth);
    } else {
        sx = 2.f / mw;
        sy = sx * ((float)g_viewWidth / g_viewHeight);
    }

    float centerX = (g_model.canvasWidth / 2.f - g_model.canvasOriginX) / g_model.pixelsPerUnit;
    float centerY = (g_model.canvasOriginY - g_model.canvasHeight / 2.f) / g_model.pixelsPerUnit;
    float tx = -centerX * sx;
    float ty = -centerY * sy;

    sx *= g_userScale;
    sy *= g_userScale;
    tx = tx * g_userScale + g_userOffsetX;
    ty = ty * g_userScale + g_userOffsetY;

    g_projMatrix[0]  = sx;
    g_projMatrix[5]  = sy;
    g_projMatrix[12] = tx;
    g_projMatrix[13] = ty;
}

// ===================== Texture loading via stb_image =====================

static GLuint loadTextureFromFile(const std::string& path) {
    auto pngData = readFile(path);
    if (pngData.empty()) { LOGE("Cannot read texture: %s", path.c_str()); return 0; }
    LOGI("PNG file: %s (%zu bytes)", path.c_str(), pngData.size());

    if (pngData.size() < 8 || pngData[0] != 0x89 || pngData[1] != 0x50
        || pngData[2] != 0x4E || pngData[3] != 0x47) {
        LOGE("Invalid PNG header"); return 0;
    }

    stbi_set_flip_vertically_on_load(1);
    int w, h, channels;
    unsigned char* pixels = stbi_load_from_memory(
        pngData.data(), (int)pngData.size(), &w, &h, &channels, 4);
    if (!pixels) {
        LOGE("stb_image decode failed: %s - %s", path.c_str(), stbi_failure_reason());
        return 0;
    }
    LOGI("stb_image decoded: %s %dx%d ch=%d", path.c_str(), w, h, channels);
    pngData.clear();
    pngData.shrink_to_fit();

    int targetW = w, targetH = h;
    int scale = 1;
    while (targetW > 2048 || targetH > 2048) {
        targetW /= 2; targetH /= 2; scale *= 2;
    }

    unsigned char* finalPixels = pixels;
    if (scale > 1) {
        LOGI("Downsampling %dx%d -> %dx%d (scale=1/%d)", w, h, targetW, targetH, scale);
        finalPixels = (unsigned char*)malloc(targetW * targetH * 4);
        if (!finalPixels) { stbi_image_free(pixels); return 0; }
        int n = scale * scale;
        for (int y = 0; y < targetH; y++) {
            for (int x = 0; x < targetW; x++) {
                int r = 0, g = 0, b = 0, a = 0;
                for (int sy2 = 0; sy2 < scale; sy2++) {
                    for (int sx2 = 0; sx2 < scale; sx2++) {
                        int srcIdx = ((y * scale + sy2) * w + (x * scale + sx2)) * 4;
                        r += pixels[srcIdx + 0];
                        g += pixels[srcIdx + 1];
                        b += pixels[srcIdx + 2];
                        a += pixels[srcIdx + 3];
                    }
                }
                int dstIdx = (y * targetW + x) * 4;
                finalPixels[dstIdx + 0] = (unsigned char)(r / n);
                finalPixels[dstIdx + 1] = (unsigned char)(g / n);
                finalPixels[dstIdx + 2] = (unsigned char)(b / n);
                finalPixels[dstIdx + 3] = (unsigned char)(a / n);
            }
        }
        stbi_image_free(pixels);
        pixels = nullptr;
    }

    GLuint texId;
    glGenTextures(1, &texId);
    glBindTexture(GL_TEXTURE_2D, texId);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, targetW, targetH, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, finalPixels);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) LOGE("glTexImage2D error: 0x%x", err);

    if (scale > 1) free(finalPixels);
    else stbi_image_free(finalPixels);

    LOGI("Texture %s -> GL %d (%dx%d)", path.c_str(), texId, targetW, targetH);
    return texId;
}

// ===================== Model Loading =====================

static bool loadModelFromFile(const std::string& modelJsonPath) {
    if (g_model.loaded) {
        for (auto t : g_model.textureIds) if (t) glDeleteTextures(1, &t);
        if (g_model.modelBuffer) free(g_model.modelBuffer);
        if (g_model.mocBuffer)   free(g_model.mocBuffer);
        g_model = Live2DModel();
    }

    size_t sl = modelJsonPath.find_last_of('/');
    g_model.modelDir = (sl != std::string::npos) ? modelJsonPath.substr(0, sl + 1) : "";

    std::string json = readFileString(modelJsonPath);
    if (json.empty()) { LOGE("Cannot read %s", modelJsonPath.c_str()); return false; }
    ModelFileInfo info = parseModel3Json(json);
    if (info.mocPath.empty()) { LOGE("No Moc in model3.json"); return false; }

    auto mocData = readFile(g_model.modelDir + info.mocPath);
    if (mocData.empty()) { LOGE("Cannot read moc3"); return false; }
    g_model.mocBuffer = alignedMalloc(mocData.size(), csmAlignofMoc);
    if (!g_model.mocBuffer) return false;
    memcpy(g_model.mocBuffer, mocData.data(), mocData.size());

    if (!csmHasMocConsistency(g_model.mocBuffer, (unsigned int)mocData.size())) {
        LOGE("Moc consistency fail"); free(g_model.mocBuffer); g_model.mocBuffer = nullptr; return false;
    }
    g_model.moc = csmReviveMocInPlace(g_model.mocBuffer, (unsigned int)mocData.size());
    if (!g_model.moc) { LOGE("Moc revive fail"); return false; }
    LOGI("Moc revived OK");

    unsigned int msz = csmGetSizeofModel(g_model.moc);
    g_model.modelBuffer = alignedMalloc(msz, csmAlignofModel);
    if (!g_model.modelBuffer) return false;
    g_model.model = csmInitializeModelInPlace(g_model.moc, g_model.modelBuffer, msz);
    if (!g_model.model) { LOGE("Model init fail"); return false; }
    LOGI("Model initialized");

    csmVector2 cs, co; float ppu;
    csmReadCanvasInfo(g_model.model, &cs, &co, &ppu);
    g_model.canvasWidth = cs.X; g_model.canvasHeight = cs.Y;
    g_model.canvasOriginX = co.X; g_model.canvasOriginY = co.Y;
    g_model.pixelsPerUnit = ppu;
    LOGI("Canvas %.0fx%.0f origin=(%.0f,%.0f) ppu=%.1f", cs.X, cs.Y, co.X, co.Y, ppu);

    int pc = csmGetParameterCount(g_model.model);
    const char** pids = csmGetParameterIds(g_model.model);
    for (int i = 0; i < pc; i++) g_model.parameterMap[pids[i]] = i;
    LOGI("Parameters: %d", pc);

    LOGI("Loading %d textures...", (int)info.texturePaths.size());
    for (size_t ti2 = 0; ti2 < info.texturePaths.size(); ti2++) {
        std::string fullPath = g_model.modelDir + info.texturePaths[ti2];
        LOGI("Texture[%d]: %s", (int)ti2, fullPath.c_str());
        GLuint tid = loadTextureFromFile(fullPath);
        g_model.textureIds.push_back(tid);
        if (tid == 0) LOGE("Texture[%d] FAILED!", (int)ti2);
    }
    LOGI("Textures loaded: %d", (int)g_model.textureIds.size());

    csmUpdateModel(g_model.model);
    g_model.loaded = true;
    updateProjection();

    // Load idle motion
    {
        size_t idlePos = findKey(json, "Idle");
        if (idlePos != std::string::npos) {
            size_t filePos = findKey(json, "File", idlePos);
            if (filePos != std::string::npos) {
                std::string mf = extractString(json, filePos);
                if (!mf.empty()) {
                    std::string mp2 = g_model.modelDir + mf;
                    std::string mj = readFileString(mp2);
                    if (!mj.empty()) {
                        g_idleMotion = parseMotion3Json(mj);
                        g_hasIdleMotion = !g_idleMotion.curves.empty();
                        g_motionTime = 0.f;
                        g_lastTime = 0.0;
                        LOGI("Idle motion: %s (%d curves, %.1fs)", mp2.c_str(),
                             (int)g_idleMotion.curves.size(), g_idleMotion.duration);
                    }
                }
            }
        }
        if (!g_hasIdleMotion) LOGI("No idle motion found");
    }

    // Load all expressions
    g_expressions.clear();
    g_currentExpressionId.clear();
    g_expressionFadeWeight = 0.f;
    g_expressionFadingIn = false;
    {
        size_t exprArr = findArrayStart(json, "Expressions");
        if (exprArr != std::string::npos) {
            auto exprObjs = extractObjectArray(json, exprArr);
            for (const auto& ej : exprObjs) {
                size_t np = findKey(ej, "Name");
                size_t fp = findKey(ej, "File");
                if (np == std::string::npos || fp == std::string::npos) continue;
                std::string ename = extractString(ej, np);
                std::string efile = extractString(ej, fp);
                if (ename.empty() || efile.empty()) continue;
                std::string fullPath = g_model.modelDir + efile;
                std::string ejson = readFileString(fullPath);
                if (!ejson.empty()) {
                    g_expressions[ename] = parseExp3Json(ejson, ename);
                }
            }
            LOGI("Expressions loaded: %d", (int)g_expressions.size());
        }
    }

    // Load motion groups
    g_motionGroups.clear();
    g_hasActiveMotion = false;
    g_activeMotionPriority = 0;
    {
        size_t motionsPos = findKey(json, "Motions");
        if (motionsPos != std::string::npos) {
            size_t braceStart = motionsPos;
            while (braceStart < json.size() && json[braceStart] != '{') braceStart++;
            if (braceStart < json.size()) {
                int depth = 0;
                size_t braceEnd = braceStart;
                while (braceEnd < json.size()) {
                    if (json[braceEnd] == '{') depth++;
                    else if (json[braceEnd] == '}') { depth--; if (depth == 0) break; }
                    braceEnd++;
                }
                std::string motionsObj = json.substr(braceStart, braceEnd - braceStart + 1);
                size_t scanPos = 1;
                while (scanPos < motionsObj.size()) {
                    size_t qStart = motionsObj.find('"', scanPos);
                    if (qStart == std::string::npos) break;
                    size_t qEnd = motionsObj.find('"', qStart + 1);
                    if (qEnd == std::string::npos) break;
                    std::string groupName = motionsObj.substr(qStart + 1, qEnd - qStart - 1);
                    if (groupName.empty()) groupName = "Default";
                    size_t arrStart = motionsObj.find('[', qEnd);
                    if (arrStart == std::string::npos) break;
                    auto entries = extractObjectArray(motionsObj, arrStart);
                    std::vector<MotionEntry> group;
                    for (const auto& entry : entries) {
                        size_t fpos = findKey(entry, "File");
                        if (fpos != std::string::npos) {
                            std::string file = extractString(entry, fpos);
                            if (!file.empty()) group.push_back({file});
                        }
                    }
                    if (!group.empty()) {
                        g_motionGroups[groupName] = group;
                        LOGI("Motion group '%s': %d entries", groupName.c_str(), (int)group.size());
                    }
                    size_t arrEnd = arrStart;
                    int adepth = 0;
                    while (arrEnd < motionsObj.size()) {
                        if (motionsObj[arrEnd] == '[') adepth++;
                        else if (motionsObj[arrEnd] == ']') { adepth--; if (adepth == 0) { arrEnd++; break; } }
                        arrEnd++;
                    }
                    scanPos = arrEnd;
                }
            }
        }
    }

    // Load physics
    {
        size_t physPos = findKey(json, "Physics");
        if (physPos != std::string::npos) {
            std::string physFile = extractString(json, physPos);
            if (!physFile.empty()) {
                std::string pp = g_model.modelDir + physFile;
                std::string pj = readFileString(pp);
                if (!pj.empty()) {
                    parsePhysics3Json(pj);
                    initPhysics();
                    LOGI("Physics loaded: %s (%d settings)", pp.c_str(), (int)g_physics.settings.size());
                }
            }
        }
        if (!g_physics.loaded) LOGI("No physics found");
    }

    // Load pose
    g_poseGroups.clear();
    g_hasPose = false;
    {
        size_t posePos = findKey(json, "Pose");
        if (posePos != std::string::npos) {
            std::string poseFile = extractString(json, posePos);
            if (!poseFile.empty()) {
                std::string pp = g_model.modelDir + poseFile;
                std::string pj = readFileString(pp);
                if (!pj.empty()) {
                    parsePose3Json(pj);
                    if (g_hasPose) {
                        initPosePartIndices();
                        LOGI("Pose initialized: %s", pp.c_str());
                    }
                }
            }
        }
        if (!g_hasPose) LOGI("No pose found");
    }

    // Reset external overrides
    g_externalOverrides.clear();

    LOGI("Model ready!");
    return true;
}

// ===================== Rendering =====================

struct DSortInfo { int index; int order; };

static void renderModel() {
    if (!g_model.loaded || !g_shader.program) return;

    double now = getCurrentTime();
    float dt = (g_lastTime > 0.0) ? (float)(now - g_lastTime) : (1.f / 60.f);
    if (dt > 0.1f) dt = 0.1f;
    g_lastTime = now;

    float* paramValues = csmGetParameterValues(g_model.model);
    const float* paramDefaults = csmGetParameterDefaultValues(g_model.model);
    const float* paramMins = csmGetParameterMinimumValues(g_model.model);
    const float* paramMaxs = csmGetParameterMaximumValues(g_model.model);
    int paramCount = csmGetParameterCount(g_model.model);

    for (int p = 0; p < paramCount; p++) paramValues[p] = paramDefaults[p];

    // Apply idle motion
    if (g_hasIdleMotion) {
        g_motionTime += dt;
        if (g_idleMotion.loop && g_motionTime >= g_idleMotion.duration)
            g_motionTime = fmodf(g_motionTime, g_idleMotion.duration);
        for (const auto& curve : g_idleMotion.curves) {
            auto it = g_model.parameterMap.find(curve.paramId);
            if (it != g_model.parameterMap.end()) {
                int pidx = it->second;
                paramValues[pidx] = std::clamp(evaluateMotionCurve(curve, g_motionTime),
                                               paramMins[pidx], paramMaxs[pidx]);
            }
        }
    }

    // Apply active motion with fade
    if (g_hasActiveMotion) {
        g_activeMotionTime += dt;
        float motionWeight = 1.0f;
        float fadeIn = g_activeMotion.fadeInTime;
        float fadeOut = g_activeMotion.fadeOutTime;
        float dur = g_activeMotion.duration;

        if (g_activeMotionTime < fadeIn && fadeIn > 0.001f) {
            motionWeight = g_activeMotionTime / fadeIn;
        } else if (!g_activeMotion.loop && g_activeMotionTime > dur - fadeOut && fadeOut > 0.001f) {
            motionWeight = (dur - g_activeMotionTime) / fadeOut;
            if (motionWeight < 0.f) motionWeight = 0.f;
        }

        if (!g_activeMotion.loop && g_activeMotionTime >= dur) {
            g_hasActiveMotion = false;
            g_activeMotionPriority = 0;
        } else {
            for (const auto& curve : g_activeMotion.curves) {
                auto it = g_model.parameterMap.find(curve.paramId);
                if (it != g_model.parameterMap.end()) {
                    int pidx = it->second;
                    float motionVal = evaluateMotionCurve(curve, g_activeMotionTime);
                    motionVal = std::clamp(motionVal, paramMins[pidx], paramMaxs[pidx]);
                    paramValues[pidx] = paramValues[pidx] * (1.f - motionWeight) + motionVal * motionWeight;
                }
            }
        }
    }

    // Apply expression with fade
    if (!g_currentExpressionId.empty()) {
        auto eit = g_expressions.find(g_currentExpressionId);
        if (eit != g_expressions.end()) {
            if (g_expressionFadingIn) {
                g_expressionFadeWeight += dt * g_expressionFadeSpeed;
                if (g_expressionFadeWeight >= 1.f) g_expressionFadeWeight = 1.f;
            } else {
                g_expressionFadeWeight -= dt * g_expressionFadeSpeed;
                if (g_expressionFadeWeight <= 0.f) {
                    g_expressionFadeWeight = 0.f;
                    g_currentExpressionId.clear();
                }
            }
            float w = g_expressionFadeWeight;
            if (w > 0.001f) {
                for (const auto& ep : eit->second.params) {
                    auto pit = g_model.parameterMap.find(ep.paramId);
                    if (pit == g_model.parameterMap.end()) continue;
                    int pidx = pit->second;
                    switch (ep.blend) {
                        case ExprBlend::Add:
                            paramValues[pidx] += ep.value * w;
                            break;
                        case ExprBlend::Multiply:
                            paramValues[pidx] *= (1.0f + (ep.value - 1.0f) * w);
                            break;
                        case ExprBlend::Overwrite:
                            paramValues[pidx] = paramValues[pidx] * (1.f - w) + ep.value * w;
                            break;
                    }
                    paramValues[pidx] = std::clamp(paramValues[pidx], paramMins[pidx], paramMaxs[pidx]);
                }
            }
        }
    }

    updatePhysics(dt);

    // Apply external overrides
    for (const auto& ov : g_externalOverrides) {
        int pidx = ov.first;
        float val = ov.second.first, weight = ov.second.second;
        if (pidx >= 0 && pidx < paramCount) {
            if (weight >= 1.f) paramValues[pidx] = val;
            else paramValues[pidx] = paramValues[pidx] * (1.f - weight) + val * weight;
        }
    }

    updatePose(dt);
    csmUpdateModel(g_model.model);

    // Get drawable data
    int dc = csmGetDrawableCount(g_model.model);
    const int*    ro   = csmGetDrawableRenderOrders(g_model.model);
    const csmFlags* df = csmGetDrawableDynamicFlags(g_model.model);
    const csmFlags* cf = csmGetDrawableConstantFlags(g_model.model);
    const int*    ti   = csmGetDrawableTextureIndices(g_model.model);
    const float*  op   = csmGetDrawableOpacities(g_model.model);
    const int*    vc   = csmGetDrawableVertexCounts(g_model.model);
    const csmVector2** vp = csmGetDrawableVertexPositions(g_model.model);
    const csmVector2** vu = csmGetDrawableVertexUvs(g_model.model);
    const int*    ic   = csmGetDrawableIndexCounts(g_model.model);
    const unsigned short** idx = csmGetDrawableIndices(g_model.model);
    const csmVector4* mc = csmGetDrawableMultiplyColors(g_model.model);
    const csmVector4* sc = csmGetDrawableScreenColors(g_model.model);
    const int*    maskCounts = csmGetDrawableMaskCounts(g_model.model);
    const int**   masks      = csmGetDrawableMasks(g_model.model);

    // Sort by render order
    std::vector<DSortInfo> sorted(dc);
    for (int i = 0; i < dc; i++) sorted[i] = {i, ro[i]};
    std::sort(sorted.begin(), sorted.end(),
              [](const DSortInfo& a, const DSortInfo& b){ return a.order < b.order; });

    // Save GLKView's FBO (on iOS this is NOT 0!)
    GLint screenFBO = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &screenFBO);

    // Ensure mask FBO exists at viewport size
    if (g_viewWidth > 0 && g_viewHeight > 0 && g_maskShader.program)
        ensureMaskFBO(g_viewWidth, g_viewHeight);

    // PreDraw
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_DEPTH_TEST);
    glEnable(GL_BLEND);
    glFrontFace(GL_CCW);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

    for (const auto& s : sorted) {
        int i = s.index;
        if (!(df[i] & csmIsVisible)) continue;
        if (op[i] <= 0.001f || vc[i] == 0 || ic[i] == 0) continue;
        int tIdx = ti[i];
        if (tIdx < 0 || tIdx >= (int)g_model.textureIds.size() || g_model.textureIds[tIdx] == 0) continue;

        bool hasMask = (maskCounts && maskCounts[i] > 0 && masks && masks[i] != nullptr
                        && g_maskFBO != 0 && g_maskedShader.program != 0);

        // ---- Render clipping mask to FBO if needed ----
        if (hasMask) {
            glBindFramebuffer(GL_FRAMEBUFFER, g_maskFBO);
            glViewport(0, 0, g_maskW, g_maskH);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            glDisable(GL_CULL_FACE);
            glBlendFuncSeparate(GL_ONE, GL_ONE, GL_ONE, GL_ONE); // additive for mask

            glUseProgram(g_maskShader.program);
            glEnableVertexAttribArray(g_maskShader.a_position);
            glEnableVertexAttribArray(g_maskShader.a_texCoord);
            glUniformMatrix4fv(g_maskShader.u_matrix, 1, GL_FALSE, g_projMatrix);
            glUniform1i(g_maskShader.u_texture, 0);
            glActiveTexture(GL_TEXTURE0);

            for (int m = 0; m < maskCounts[i]; m++) {
                int mi = masks[i][m];
                if (mi < 0 || mi >= dc) continue;
                if (vc[mi] == 0 || ic[mi] == 0) continue;
                int mtIdx = ti[mi];
                if (mtIdx < 0 || mtIdx >= (int)g_model.textureIds.size() || g_model.textureIds[mtIdx] == 0) continue;

                glBindTexture(GL_TEXTURE_2D, g_model.textureIds[mtIdx]);
                glUniform1f(g_maskShader.u_opacity, op[mi]);
                glVertexAttribPointer(g_maskShader.a_position, 2, GL_FLOAT, GL_FALSE, 0, vp[mi]);
                glVertexAttribPointer(g_maskShader.a_texCoord, 2, GL_FLOAT, GL_FALSE, 0, vu[mi]);
                glDrawElements(GL_TRIANGLES, ic[mi], GL_UNSIGNED_SHORT, idx[mi]);
            }

            glDisableVertexAttribArray(g_maskShader.a_position);
            glDisableVertexAttribArray(g_maskShader.a_texCoord);

            // Check for GL errors during mask rendering
            GLenum maskErr = glGetError();
            (void)maskErr; // consume error silently

            // Restore GLKView's framebuffer (NOT 0 on iOS!)
            glBindFramebuffer(GL_FRAMEBUFFER, screenFBO);
            glViewport(0, 0, g_viewWidth, g_viewHeight);
        }

        // ---- Draw the actual drawable ----
        // Per-drawable culling
        if (cf[i] & csmIsDoubleSided) glDisable(GL_CULL_FACE);
        else { glEnable(GL_CULL_FACE); glCullFace(GL_BACK); }

        // Blend mode
        if (cf[i] & csmBlendAdditive)
            glBlendFuncSeparate(GL_ONE, GL_ONE, GL_ZERO, GL_ONE);
        else if (cf[i] & csmBlendMultiplicative)
            glBlendFuncSeparate(GL_DST_COLOR, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
        else
            glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        if (hasMask) {
            // Use masked shader with FBO mask texture
            glUseProgram(g_maskedShader.program);
            glEnableVertexAttribArray(g_maskedShader.a_position);
            glEnableVertexAttribArray(g_maskedShader.a_texCoord);
            glUniformMatrix4fv(g_maskedShader.u_matrix, 1, GL_FALSE, g_projMatrix);
            glUniform1i(g_maskedShader.u_texture, 0);

            // Bind mask texture to unit 1
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, g_maskTexture);
            glUniform1i(g_maskedShader.u_mask, 1);
            glUniform2f(g_maskedShader.u_viewportSize, (float)g_viewWidth, (float)g_viewHeight);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, g_model.textureIds[tIdx]);

            glUniform1f(g_maskedShader.u_opacity, op[i]);
            if (mc) glUniform4f(g_maskedShader.u_multiplyColor, mc[i].X, mc[i].Y, mc[i].Z, mc[i].W);
            else    glUniform4f(g_maskedShader.u_multiplyColor, 1, 1, 1, 1);
            if (sc) glUniform4f(g_maskedShader.u_screenColor, sc[i].X, sc[i].Y, sc[i].Z, sc[i].W);
            else    glUniform4f(g_maskedShader.u_screenColor, 0, 0, 0, 0);

            glVertexAttribPointer(g_maskedShader.a_position, 2, GL_FLOAT, GL_FALSE, 0, vp[i]);
            glVertexAttribPointer(g_maskedShader.a_texCoord, 2, GL_FLOAT, GL_FALSE, 0, vu[i]);
            glDrawElements(GL_TRIANGLES, ic[i], GL_UNSIGNED_SHORT, idx[i]);

            glDisableVertexAttribArray(g_maskedShader.a_position);
            glDisableVertexAttribArray(g_maskedShader.a_texCoord);
        } else {
            // Use normal shader
            glUseProgram(g_shader.program);
            glEnableVertexAttribArray(g_shader.a_position);
            glEnableVertexAttribArray(g_shader.a_texCoord);
            glUniformMatrix4fv(g_shader.u_matrix, 1, GL_FALSE, g_projMatrix);
            glUniform1i(g_shader.u_texture, 0);
            glActiveTexture(GL_TEXTURE0);

            glBindTexture(GL_TEXTURE_2D, g_model.textureIds[tIdx]);
            glUniform1f(g_shader.u_opacity, op[i]);
            if (mc) glUniform4f(g_shader.u_multiplyColor, mc[i].X, mc[i].Y, mc[i].Z, mc[i].W);
            else    glUniform4f(g_shader.u_multiplyColor, 1, 1, 1, 1);
            if (sc) glUniform4f(g_shader.u_screenColor, sc[i].X, sc[i].Y, sc[i].Z, sc[i].W);
            else    glUniform4f(g_shader.u_screenColor, 0, 0, 0, 0);

            glVertexAttribPointer(g_shader.a_position, 2, GL_FLOAT, GL_FALSE, 0, vp[i]);
            glVertexAttribPointer(g_shader.a_texCoord, 2, GL_FLOAT, GL_FALSE, 0, vu[i]);
            glDrawElements(GL_TRIANGLES, ic[i], GL_UNSIGNED_SHORT, idx[i]);

            glDisableVertexAttribArray(g_shader.a_position);
            glDisableVertexAttribArray(g_shader.a_texCoord);
        }
    }

    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    csmResetDrawableDynamicFlags(g_model.model);
}

// ===================== C API (Bridge) =====================

extern "C" {

int L2DBridge_Init(void) {
    csmVersion v = csmGetVersion();
    LOGI("Cubism Core %d.%d.%d", (v>>24)&0xFF, (v>>16)&0xFF, v&0xFFFF);

    g_shader       = ShaderInfo();
    g_maskShader   = MaskShaderInfo();
    g_maskedShader = MaskedShaderInfo();
    g_maskFBO      = 0;
    g_maskTexture  = 0;
    g_maskW        = 0;
    g_maskH        = 0;

    initShaders();
    initMaskShaders();

    if (g_model.loaded) {
        g_model.textureIds.clear();
        g_model.loaded = false;
    }

    g_initialized = true;
    LOGI("Live2D Bridge initialized");
    return 1;
}

int L2DBridge_LoadModel(const char* modelJsonPath) {
    if (!modelJsonPath) return 0;
    LOGI("Loading model: %s", modelJsonPath);
    bool ok = loadModelFromFile(std::string(modelJsonPath));
    LOGI("Model load %s", ok ? "OK" : "FAIL");
    return ok ? 1 : 0;
}

void L2DBridge_OnSurfaceChanged(int width, int height) {
    glViewport(0, 0, width, height);
    if (g_viewWidth == width && g_viewHeight == height) return;
    g_viewWidth = width; g_viewHeight = height;
    updateProjection();
    LOGI("Surface: %dx%d", width, height);
}

void L2DBridge_OnDrawFrame(void) {
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glDisable(GL_DEPTH_TEST);
    if (g_initialized && g_model.loaded) renderModel();
}

void L2DBridge_SetParameterValue(const char* paramId, float value, float weight) {
    if (!g_model.loaded || !paramId) return;
    auto it = g_model.parameterMap.find(paramId);
    if (it != g_model.parameterMap.end()) {
        if (weight < 0.001f)
            g_externalOverrides.erase(it->second);
        else
            g_externalOverrides[it->second] = {value, weight};
    }
}

void L2DBridge_StartMotion(const char* group, int index, int priority) {
    if (!g_model.loaded || !group) return;
    std::string groupStr(group);
    LOGI("StartMotion: %s[%d] p=%d", groupStr.c_str(), index, priority);

    if (g_hasActiveMotion && priority < g_activeMotionPriority) {
        LOGI("Motion rejected: priority %d < current %d", priority, g_activeMotionPriority);
        return;
    }

    auto git = g_motionGroups.find(groupStr);
    if (git == g_motionGroups.end()) {
        LOGI("Motion group '%s' not found", groupStr.c_str());
        return;
    }
    if (index < 0 || index >= (int)git->second.size()) {
        LOGI("Motion index %d out of range", index);
        return;
    }

    std::string motionFile = g_model.modelDir + git->second[index].file;
    std::string mj = readFileString(motionFile);
    if (mj.empty()) {
        LOGE("Cannot read motion file: %s", motionFile.c_str());
        return;
    }

    g_activeMotion = parseMotion3Json(mj);
    if (g_activeMotion.curves.empty()) return;

    g_hasActiveMotion = true;
    g_activeMotionTime = 0.f;
    g_activeMotionPriority = priority;
    LOGI("Active motion started: %s (%.1fs)", motionFile.c_str(), g_activeMotion.duration);
}

void L2DBridge_SetExpression(const char* expressionId) {
    if (!expressionId) return;
    std::string exprId(expressionId);

    if (exprId.empty()) {
        if (!g_currentExpressionId.empty()) {
            g_expressionFadingIn = false;
        }
        return;
    }

    if (g_expressions.find(exprId) == g_expressions.end()) {
        LOGI("Expression '%s' not found", exprId.c_str());
        return;
    }

    if (exprId != g_currentExpressionId) {
        g_currentExpressionId = exprId;
        g_expressionFadeWeight = 0.f;
    }
    g_expressionFadingIn = true;
}

void L2DBridge_SetModelTransform(float scale, float offsetX, float offsetY) {
    g_userScale   = scale;
    g_userOffsetX = offsetX;
    g_userOffsetY = offsetY;
    updateProjection();
}

int L2DBridge_IsModelLoaded(void) {
    return g_model.loaded ? 1 : 0;
}

float L2DBridge_GetParameterValue(const char* paramId) {
    if (!g_model.loaded || !paramId) return 0.f;
    auto it = g_model.parameterMap.find(paramId);
    if (it != g_model.parameterMap.end())
        return csmGetParameterValues(g_model.model)[it->second];
    return 0.f;
}

float L2DBridge_GetParameterRange(const char* paramId) {
    if (!g_model.loaded || !paramId) return 1.f;
    auto it = g_model.parameterMap.find(paramId);
    if (it != g_model.parameterMap.end()) {
        int pidx = it->second;
        return csmGetParameterMaximumValues(g_model.model)[pidx]
             - csmGetParameterMinimumValues(g_model.model)[pidx];
    }
    return 1.f;
}

unsigned int L2DBridge_GetCoreVersion(void) {
    return csmGetVersion();
}

void L2DBridge_Cleanup(void) {
    if (g_model.loaded) {
        for (auto t : g_model.textureIds) if (t) glDeleteTextures(1, &t);
        if (g_model.modelBuffer) free(g_model.modelBuffer);
        if (g_model.mocBuffer)   free(g_model.mocBuffer);
        g_model = Live2DModel();
    }
    if (g_shader.program) { glDeleteProgram(g_shader.program); g_shader = ShaderInfo(); }
    if (g_maskShader.program) { glDeleteProgram(g_maskShader.program); g_maskShader = MaskShaderInfo(); }
    if (g_maskedShader.program) { glDeleteProgram(g_maskedShader.program); g_maskedShader = MaskedShaderInfo(); }
    if (g_maskFBO) { glDeleteFramebuffers(1, &g_maskFBO); g_maskFBO = 0; }
    if (g_maskTexture) { glDeleteTextures(1, &g_maskTexture); g_maskTexture = 0; }
    g_initialized = false;
    LOGI("Cleanup done");
}

} // extern "C"
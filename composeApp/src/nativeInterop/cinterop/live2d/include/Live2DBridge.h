/**
 * Live2D Bridge — C API for Kotlin/Native cinterop.
 *
 * This header defines a pure-C interface that wraps the Cubism Core C API
 * plus OpenGL ES rendering logic. Kotlin/Native calls these functions via cinterop.
 * The implementation is in Live2DBridge.m (Objective-C++ with OpenGL ES).
 */

#ifndef LIVE2D_BRIDGE_H
#define LIVE2D_BRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Initialize the Live2D rendering engine.
 * Must be called on the GL thread after EAGLContext is set up.
 * @return 1 on success, 0 on failure.
 */
int L2DBridge_Init(void);

/**
 * Load a Live2D model from the filesystem.
 * @param modelJsonPath  Absolute path to the .model3.json file.
 * @return 1 on success, 0 on failure.
 */
int L2DBridge_LoadModel(const char* modelJsonPath);

/**
 * Notify the renderer of a viewport size change.
 * @param width   Viewport width in pixels.
 * @param height  Viewport height in pixels.
 */
void L2DBridge_OnSurfaceChanged(int width, int height);

/**
 * Render one frame. Call this on the GL thread each display refresh.
 */
void L2DBridge_OnDrawFrame(void);

/**
 * Set a parameter override value.
 * @param paramId  The parameter ID string (e.g., "ParamMouthOpenY").
 * @param value    The target value.
 * @param weight   Blend weight (0..1). 0 removes the override.
 */
void L2DBridge_SetParameterValue(const char* paramId, float value, float weight);

/**
 * Start a motion from the model's motion groups.
 * @param group    Motion group name (e.g., "Idle", "TapBody").
 * @param index    Index within the group.
 * @param priority Motion priority (higher overrides lower).
 */
void L2DBridge_StartMotion(const char* group, int index, int priority);

/**
 * Apply an expression.
 * @param expressionId  Expression name (e.g., "exp_01"). Empty string clears.
 */
void L2DBridge_SetExpression(const char* expressionId);

/**
 * Set user model transform (drag & pinch zoom).
 * @param scale    Zoom factor (1.0 = original).
 * @param offsetX  Horizontal NDC offset (-1..1).
 * @param offsetY  Vertical NDC offset (-1..1).
 */
void L2DBridge_SetModelTransform(float scale, float offsetX, float offsetY);

/**
 * Check if a model is currently loaded and ready for rendering.
 * @return 1 if loaded, 0 otherwise.
 */
int L2DBridge_IsModelLoaded(void);

/**
 * Get the current value of a parameter by ID.
 * @param paramId  The parameter ID string.
 * @return Current value, or 0 if not found.
 */
float L2DBridge_GetParameterValue(const char* paramId);

/**
 * Get the range (max - min) of a parameter by ID.
 * @param paramId  The parameter ID string.
 * @return Range, or 1 if not found.
 */
float L2DBridge_GetParameterRange(const char* paramId);

/**
 * Get the Cubism Core version as a packed integer.
 * @return Version in format 0xMMmmPPPP.
 */
unsigned int L2DBridge_GetCoreVersion(void);

/**
 * Release all resources. Call before destroying GL context.
 */
void L2DBridge_Cleanup(void);

#ifdef __cplusplus
}
#endif

#endif /* LIVE2D_BRIDGE_H */

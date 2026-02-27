// stb_image implementation for iOS — compiled separately to avoid multiple definition issues
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG      // Only need PNG decoder
#define STBI_NO_STDIO      // No file I/O, use memory-based decoding
#define STBI_NO_HDR        // No HDR support needed
#define STBI_NO_LINEAR     // No linear float conversion needed
#include "stb_image.h"

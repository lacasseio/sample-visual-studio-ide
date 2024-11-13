#pragma once
#if defined(_MSC_VER)
#if defined(BUILD_DLL)
#define IMPORT_EXPORT __declspec(dllexport)
#else
#define IMPORT_EXPORT __declspec(dllimport)
#endif
#endif

IMPORT_EXPORT int foo();
# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Minecraft mod that extends Applied Energistics 2 (AE2). The mod adds a **Recursive Pattern Printer** block that automates pattern creation by recursively analyzing crafting dependencies.

### Core Functionality
- User inserts a pattern and blank patterns into the block
- The block analyzes the pattern's crafting dependencies recursively
- For each dependency that doesn't already have a pattern in the ME network, it creates one
- Patterns are output one at a time to an output slot
- User must remove each pattern before the next one prints

## Build Commands

```bash
# Build the mod
./gradlew build

# Run client for testing
./gradlew runClient

# Run server for testing
./gradlew runServer

# Generate run configurations for IDE
./gradlew genEclipseRuns   # Eclipse
./gradlew genIntellijRuns  # IntelliJ IDEA

# Run data generators (for assets/recipes)
./gradlew runData
```

## Architecture

### Dependencies
- **Minecraft** (NeoForge/Forge modloader)
- **Applied Energistics 2** - Required dependency, provides ME network APIs

### Key AE2 Integration Points
- `IGridNode` / `IGridService` - For connecting to ME networks
- `ICraftingService` - For querying existing patterns in the network
- `IPatternDetails` - For reading pattern data
- Pattern encoding/decoding utilities from AE2 API

### Block Structure
- **RecursivePatternPrinterBlock** - The main block
- **RecursivePatternPrinterBlockEntity** - Handles logic, inventory, and ME network connection
- **RecursivePatternPrinterMenu** - Container for GUI interaction
- **RecursivePatternPrinterScreen** - Client-side GUI rendering

### Textures
Base the block texture on AE2's ME Extended IO Port texture with a modified color tone to distinguish it while maintaining visual consistency with AE2's aesthetic.

## Package Structure (Recommended)

```
com.example.ae2rpp/
├── AE2RecursivePatternPrinter.java  # Main mod class
├── block/
│   └── RecursivePatternPrinterBlock.java
├── blockentity/
│   └── RecursivePatternPrinterBlockEntity.java
├── menu/
│   └── RecursivePatternPrinterMenu.java
├── screen/
│   └── RecursivePatternPrinterScreen.java
├── init/
│   ├── ModBlocks.java
│   ├── ModBlockEntities.java
│   └── ModMenuTypes.java
└── network/
    └── PatternAnalyzer.java  # Recursive dependency analysis logic
```

## Recipe System Integration

The pattern analyzer must:
1. Extract the crafting recipe from the input pattern
2. Identify all ingredient items
3. Query the ME network's crafting service for existing patterns
4. For items without patterns, look up their recipes via Minecraft's RecipeManager
5. Recursively process those recipes
6. Track visited items to avoid infinite loops in circular recipes

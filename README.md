# Checkpoint 2: [Click this Google Docs Link](https://docs.google.com/document/d/1FFu8siOhSZKjk2c7cd1_C3wuPN1SpfxMNNl0Gxcbs4g/edit?usp=sharing)

# Checkpoint 1: [Click this Google Docs Link](https://docs.google.com/document/d/1QKnx89y35HhnMf1gYEA3LE6GNnbJjlhUJF2-Rjw6fWA/edit?usp=sharing)

# Project Proposal: MindCraft: Text-Conditioned Infinite Worlds

**Sophia Zhang (sophiazh)**

Text-conditioned infinite world generation typically requires massive compute (world models); on the other hand, procedural infinite world generation often lacks fine-grained text control (Terrain Diffusion, etc.). MindCraft bridges this gap by utilizing LLM agents that control diffusion-based infinite terrain generation while maintaining the efficiency of Minecraft’s game engine. I will compare MindCraft’s performance (compute requirements and FPS) and creative control (user studies) with both text-conditioned autoregressive world models, Terrain Diffusion, and vanilla Minecraft parameter tweaking.

**Input:** A natural language text prompt describing the desired world. Examples:

- “Rolling mountains of cherry blossoms, with an endless river running through the valleys”
- “An archipelago with tropical islands and sand beaches”

**Output:** A fully playable, infinite Minecraft world whose terrain, biomes, and climate match the description, streamed real-time as the player explores.

**Constraints:**

- **Speed:** The pipeline should be fast enough to stream new chunks ahead of the player without being noticed too much on average consumer hardware.
- **Control:** The world needs to match the description with as much detail as possible.

**Task list:**

- Clone and run https://github.com/xandergos/terrain-diffusion with the Minecraft mod https://github.com/xandergos/terrain-diffusion-mc. This will be my starting codebase, it allows for unconditional Minecraft terrain generation with small diffusion models (https://www.youtube.com/watch?v=GtPvX62bO30&t=11s).
- Profile the existing pipeline
- Add a UI section in the Minecraft mod menu for the world creation text prompt
- Build the LLM conditioning layer (prompt schemas, etc.) for the coarse stage
  - Nice-to-have: Attempt to replace the coarse stage with grayscale SD + heuristics
- Build the LLM conditioning layer for biome control in the Minecraft mod
  - Nice-to-have: Add support for newer biomes and expose them to the LLM
- Run the compute/time comparison with an open-source world model with equivalent prompts (e.g. https://github.com/robbyant/lingbot-world), as well as vanilla minecraft
- Run user studies with ~10 people (super small scale for this project) to measure creative control, give them this vs. world model vs. vanilla minecraft parameter tweaking

**Other nice-to-haves:**  
Support for structures e.g. villages, sand temples

**Expected deliverables and evaluation:**  
The primary demo is a live screen recording (or live demo but that’s risky) showing:

- I type a prompt and press enter
- Within a few seconds, I’m walking through a Minecraft world that matches the description

The primary graphs/evaluations I will show are:

- **Speed:** Bar chart comparing time to first playable chunk and sustained FPS between MindCraft, a video-based world model, and vanilla Minecraft generation.
- **Creative control:** A user study (target 10 people for now) in which participants rate on a standardized scale how well each system’s output matches a given prompt, as well as for a video-based world model and vanilla Minecraft parameter tweaking.

**Biggest risks:**

- I’m wondering whether this is possible on my Mac, even though the Terrain Diffusion codebase is documented to run on Mac CPU.
- LLM conditioning might be too weak given the limited availability of Minecraft biomes and the flexibility of tweaking noise parameters (and in the future, Stable Diffusion)

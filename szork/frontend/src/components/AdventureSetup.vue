<template>
  <div class="adventure-setup">
    <div class="setup-content">
      <transition name="fade" mode="out-in">
        <!-- Theme Selection -->
        <div v-if="currentStep === 'theme'" class="setup-step">
          <h2 class="setup-title">Choose Your Adventure Theme</h2>
          
          <div class="theme-grid">
            <div 
              v-for="theme in predefinedThemes" 
              :key="theme.id"
              class="theme-card"
              :class="{ selected: selectedTheme === theme.id }"
              @click="selectTheme(theme.id)"
            >
              <div class="theme-icon">{{ theme.icon }}</div>
              <h3 class="theme-name">{{ theme.name }}</h3>
              <p class="theme-description">{{ theme.description }}</p>
            </div>
            
            <!-- Custom Theme Option -->
            <div 
              class="theme-card custom-theme"
              :class="{ selected: selectedTheme === 'custom' }"
              @click="selectTheme('custom')"
            >
              <div class="theme-icon">✨</div>
              <h3 class="theme-name">Custom Adventure</h3>
              <p class="theme-description">Create your own unique adventure setting</p>
            </div>
          </div>
          
          <!-- Custom Theme Input -->
          <div v-if="selectedTheme === 'custom'" class="custom-theme-input">
            <v-text-field
              v-model="customThemeInput"
              label="Describe your adventure theme"
              placeholder="e.g., A steampunk airship exploring floating islands..."
              variant="outlined"
              :error-messages="customThemeError"
              :loading="validatingCustomTheme"
              @blur="validateCustomTheme"
            />
          </div>
          
          <div class="action-buttons">
            <v-btn
              color="primary"
              size="large"
              :disabled="!canProceedFromTheme"
              @click="proceedToStyle"
            >
              Continue to Art Style
            </v-btn>
          </div>
        </div>
        
        <!-- Art Style Selection -->
        <div v-else-if="currentStep === 'style'" class="setup-step">
          <h2 class="setup-title">Choose Your Visual Style</h2>
          
          <div class="style-grid">
            <div 
              v-for="style in artStyles" 
              :key="style.id"
              class="style-card"
              :class="{ selected: selectedStyle === style.id }"
              @click="selectStyle(style.id)"
            >
              <div class="style-preview">
                <img 
                  v-if="style.sampleImage" 
                  :src="style.sampleImage" 
                  :alt="style.name + ' sample'"
                  class="style-sample-image"
                />
                <div v-else class="style-gradient" :style="{ background: style.gradient }"></div>
              </div>
              <h3 class="style-name">{{ style.name }}</h3>
              <p class="style-description">{{ style.description }}</p>
            </div>
          </div>
          
          <div class="action-buttons">
            <v-btn
              variant="outlined"
              size="large"
              @click="goBackToTheme"
              class="mr-2"
            >
              Back
            </v-btn>
            <v-btn
              color="primary"
              size="large"
              :disabled="!selectedStyle"
              @click="startAdventure"
              :loading="startingGame"
            >
              Begin Adventure
            </v-btn>
          </div>
        </div>
      </transition>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent, ref, computed } from "vue";
import axios from "axios";

interface Theme {
  id: string;
  name: string;
  description: string;
  icon: string;
  prompt?: string;
}

interface ArtStyle {
  id: string;
  name: string;
  description: string;
  gradient: string;
  sampleImage?: string;
}

export default defineComponent({
  name: "AdventureSetup",
  emits: ["adventure-ready"],
  setup(_, { emit }) {
    const currentStep = ref<"theme" | "style">("theme");
    const selectedTheme = ref<string | null>(null);
    const selectedStyle = ref<string | null>(null);
    const customThemeInput = ref("");
    const customThemeError = ref("");
    const validatingCustomTheme = ref(false);
    const startingGame = ref(false);
    const validatedCustomTheme = ref("");
    
    const predefinedThemes: Theme[] = [
      {
        id: "underground",
        name: "Underground Realm",
        icon: "⛏️",
        description: "Cave systems, mines, and subterranean kingdoms filled with crystals, ancient ruins, and hidden treasures",
        prompt: "underground caverns with glowing crystals, ancient dwarven ruins, underground rivers, and mysterious tunnels"
      },
      {
        id: "ancient",
        name: "Ancient Mysteries",
        icon: "🏛️",
        description: "Pyramids, temples, enchanted forests, and mystical ruins with hieroglyphic puzzles, nature magic, and archaeological treasures",
        prompt: "ancient temples, mystical forests, magical ruins, hieroglyphic puzzles, and archaeological discoveries"
      },
      {
        id: "estate",
        name: "Abandoned Estate",
        icon: "🏰",
        description: "Mansions, castles, and towers with secret passages, mysterious artifacts, and family treasures",
        prompt: "gothic mansion with secret passages, mysterious artifacts, haunted rooms, and family secrets"
      },
      {
        id: "island",
        name: "Lost Island",
        icon: "🏝️",
        description: "Tropical or arctic expeditions featuring shipwrecks, pirate gold, and survival challenges",
        prompt: "mysterious island with shipwrecks, pirate treasures, jungle temples, and survival challenges"
      },
      {
        id: "space",
        name: "Space Frontier",
        icon: "🚀",
        description: "Space stations and alien worlds with technology puzzles, resource management, and cosmic mysteries",
        prompt: "space station orbiting alien worlds, advanced technology, cosmic mysteries, and alien encounters"
      },
      {
        id: "nautical",
        name: "Nautical Depths",
        icon: "🌊",
        description: "Underwater adventures in submarines or sunken cities, with pressure puzzles and oceanic treasures",
        prompt: "underwater exploration in sunken cities, submarine adventures, oceanic treasures, and deep sea mysteries"
      },
      {
        id: "noir",
        name: "Noir City",
        icon: "🕵️",
        description: "Rain-slicked streets, speakeasies, and office buildings hiding criminal treasures, coded messages, and conspiracy puzzles",
        prompt: "noir detective story in rain-slicked city streets, speakeasies, criminal underworld, and conspiracy mysteries"
      },
      {
        id: "cyberpunk",
        name: "Cyberpunk Metropolis",
        icon: "🌃",
        description: "Neon-lit corporate towers, digital vaults, and underground networks with hacking puzzles and data treasures",
        prompt: "cyberpunk metropolis with neon lights, corporate towers, digital networks, and hacking challenges"
      }
    ];
    
    const artStyles: ArtStyle[] = [
      {
        id: "pixel",
        name: "Pixel Art",
        description: "Classic retro-style scenes with detailed pixelated environments, like 16-bit era adventure games",
        gradient: "linear-gradient(45deg, #8B4513, #228B22, #4169E1)",
        sampleImage: "/style-samples/pixel-sample.png"
      },
      {
        id: "illustration",
        name: "Pencil Art",
        description: "Professional pencil drawings with detailed shading, realistic textures, and fine graphite work - like an artist's sketchbook",
        gradient: "linear-gradient(45deg, #F5F5F5, #B8B8B8, #4A4A4A)",
        sampleImage: "/style-samples/illustration-sample.png"
      },
      {
        id: "painting",
        name: "Painting",
        description: "Fully rendered atmospheric scenes with realistic lighting, textures, and depth - like concept art or fantasy book covers",
        gradient: "linear-gradient(45deg, #4B0082, #800080, #FF69B4)",
        sampleImage: "/style-samples/painting-sample.png"
      },
      {
        id: "comic",
        name: "Comic/Graphic Novel",
        description: "Bold lines with cel-shaded coloring, dramatic perspectives, and stylized environments that pop off the screen",
        gradient: "linear-gradient(45deg, #FF0000, #FFD700, #000000)",
        sampleImage: "/style-samples/comic-sample.png"
      }
    ];
    
    const canProceedFromTheme = computed(() => {
      if (selectedTheme.value === "custom") {
        return customThemeInput.value.trim().length > 10 && !customThemeError.value && !validatingCustomTheme.value;
      }
      return selectedTheme.value !== null;
    });
    
    const selectTheme = (themeId: string) => {
      selectedTheme.value = themeId;
      if (themeId !== "custom") {
        customThemeError.value = "";
        validatedCustomTheme.value = "";
      }
    };
    
    const selectStyle = (styleId: string) => {
      selectedStyle.value = styleId;
    };
    
    const validateCustomTheme = async () => {
      if (customThemeInput.value.trim().length < 10) {
        customThemeError.value = "Please provide a more detailed description";
        return;
      }
      
      validatingCustomTheme.value = true;
      customThemeError.value = "";
      
      try {
        const response = await axios.post("/api/game/validate-theme", {
          theme: customThemeInput.value
        });
        
        if (response.data.valid) {
          validatedCustomTheme.value = response.data.enhancedTheme || customThemeInput.value;
          customThemeError.value = "";
        } else {
          customThemeError.value = response.data.message || "This theme may not work well for an adventure game";
        }
      } catch (error) {
        console.error("Error validating theme:", error);
        // Allow the theme anyway if validation fails
        validatedCustomTheme.value = customThemeInput.value;
      } finally {
        validatingCustomTheme.value = false;
      }
    };
    
    const proceedToStyle = () => {
      if (selectedTheme.value === "custom" && !validatedCustomTheme.value) {
        validateCustomTheme().then(() => {
          if (!customThemeError.value) {
            currentStep.value = "style";
          }
        });
      } else {
        currentStep.value = "style";
      }
    };
    
    const goBackToTheme = () => {
      currentStep.value = "theme";
    };
    
    const startAdventure = () => {
      if (!selectedTheme.value || !selectedStyle.value) return;
      
      startingGame.value = true;
      
      // Get the theme details
      let themeData;
      if (selectedTheme.value === "custom") {
        themeData = {
          id: "custom",
          name: "Custom Adventure",
          prompt: validatedCustomTheme.value || customThemeInput.value
        };
      } else {
        const theme = predefinedThemes.find(t => t.id === selectedTheme.value);
        themeData = {
          id: theme?.id,
          name: theme?.name,
          prompt: theme?.prompt
        };
      }
      
      // Get the style details
      const style = artStyles.find(s => s.id === selectedStyle.value);
      
      emit("adventure-ready", {
        theme: themeData,
        style: {
          id: style?.id,
          name: style?.name
        }
      });
    };
    
    return {
      currentStep,
      selectedTheme,
      selectedStyle,
      customThemeInput,
      customThemeError,
      validatingCustomTheme,
      startingGame,
      predefinedThemes,
      artStyles,
      canProceedFromTheme,
      selectTheme,
      selectStyle,
      validateCustomTheme,
      proceedToStyle,
      goBackToTheme,
      startAdventure
    };
  }
});
</script>

<style scoped>
.adventure-setup {
  width: 100%;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0d0d0d 0%, #1a1a2e 100%);
  padding: 2rem;
}

.setup-content {
  width: 100%;
  max-width: 1200px;
}

.setup-step {
  animation: fadeIn 0.5s ease-in;
}

.setup-title {
  text-align: center;
  font-size: 2.5rem;
  margin-bottom: 3rem;
  color: #fff;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.5);
}

.theme-grid, .style-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 1.5rem;
  margin-bottom: 3rem;
}

.theme-card, .style-card {
  background: rgba(255, 255, 255, 0.05);
  border: 2px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  padding: 1.5rem;
  cursor: pointer;
  transition: all 0.3s ease;
  backdrop-filter: blur(10px);
}

.theme-card:hover, .style-card:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.3);
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
}

.theme-card.selected, .style-card.selected {
  background: rgba(33, 150, 243, 0.2);
  border-color: #2196F3;
  box-shadow: 0 0 20px rgba(33, 150, 243, 0.3);
}

.theme-icon {
  font-size: 3rem;
  text-align: center;
  margin-bottom: 1rem;
}

.theme-name, .style-name {
  font-size: 1.3rem;
  margin-bottom: 0.5rem;
  color: #fff;
}

.theme-description, .style-description {
  font-size: 0.9rem;
  color: rgba(255, 255, 255, 0.7);
  line-height: 1.4;
}

.style-preview {
  height: 150px;
  border-radius: 8px;
  margin-bottom: 1rem;
  overflow: hidden;
  background: #1a1a1a;
}

.style-sample-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.style-gradient {
  width: 100%;
  height: 100%;
  box-shadow: inset 0 2px 8px rgba(0, 0, 0, 0.3);
}

.custom-theme-input {
  max-width: 600px;
  margin: 2rem auto;
}

.action-buttons {
  display: flex;
  justify-content: center;
  gap: 1rem;
  margin-top: 3rem;
}

.fade-enter-active, .fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from, .fade-leave-to {
  opacity: 0;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 768px) {
  .theme-grid, .style-grid {
    grid-template-columns: 1fr;
  }
  
  .setup-title {
    font-size: 1.8rem;
  }
}
</style>
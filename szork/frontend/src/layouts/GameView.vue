<template>
  <v-container fluid class="game-container pa-0">
    <!-- Intro Screen -->
    <div v-if="!gameStarted && !setupStarted" class="intro-screen">
      <div class="intro-content">
        <img src="/SZork_intro.webp" alt="Welcome to Szork" class="intro-image" />
        <v-btn 
          color="primary" 
          size="x-large" 
          class="begin-button"
          @click="beginSetup"
          elevation="8"
        >
          Begin Your Adventure
        </v-btn>
      </div>
    </div>
    
    <!-- Adventure Setup Screen -->
    <AdventureSetup 
      v-else-if="setupStarted && !gameStarted"
      @adventure-ready="onAdventureReady"
    />
    
    <!-- Game Screen -->
    <v-row v-else class="game-row ma-0">
      <v-col cols="12" md="8" class="mx-auto game-col pa-0">
        <div class="game-wrapper">
          <v-card-title class="text-h5 game-title">
            <v-row align="center" justify="space-between">
              <v-col cols="auto">
                SZork - Generative Adventuring
              </v-col>
              <v-col cols="auto">
                <div class="audio-controls">
                  <v-btn
                    @click="saveGame"
                    color="success"
                    variant="outlined"
                    icon
                    size="small"
                    title="Save Game"
                    class="mr-2"
                    v-if="currentGameId"
                  >
                    <v-icon>mdi-content-save</v-icon>
                  </v-btn>
                  <v-btn
                    @click="backgroundMusicEnabled = !backgroundMusicEnabled"
                    :color="backgroundMusicEnabled ? 'primary' : 'grey'"
                    :variant="backgroundMusicEnabled ? 'flat' : 'outlined'"
                    icon
                    size="small"
                    :title="backgroundMusicEnabled ? 'Background Music Enabled' : 'Background Music Disabled'"
                    class="mr-2"
                  >
                    <v-icon>{{ backgroundMusicEnabled ? 'mdi-music' : 'mdi-music-off' }}</v-icon>
                  </v-btn>
                  <v-btn
                    @click="narrationEnabled = !narrationEnabled"
                    :color="narrationEnabled ? 'success' : 'grey'"
                    :variant="narrationEnabled ? 'flat' : 'outlined'"
                    icon
                    size="small"
                    :title="narrationEnabled ? 'Narration Enabled' : 'Narration Disabled'"
                  >
                    <v-icon>{{ narrationEnabled ? 'mdi-account-voice' : 'mdi-account-voice-off' }}</v-icon>
                  </v-btn>
                </div>
              </v-col>
            </v-row>
          </v-card-title>
          
          <div class="game-output-wrapper">
            <div class="game-output" ref="gameOutput">
              <div
                v-for="(message, index) in messages"
                :key="index"
                class="game-message"
                :class="message.type"
              >
                <div v-if="message.image || message.imageLoading" class="scene-image-container">
                  <img 
                    v-if="message.image"
                    :src="'data:image/png;base64,' + message.image" 
                    alt="Scene visualization"
                    class="scene-image"
                  />
                  <div v-else-if="message.imageLoading" class="image-loading">
                    <v-progress-circular
                      indeterminate
                      color="primary"
                      size="40"
                      width="3"
                    ></v-progress-circular>
                  </div>
                </div>
                <div class="message-text">
                  {{ message.text }}
                  <div v-if="message.scene && message.scene.exits && message.scene.exits.length > 0" class="exits-info">
                    <strong>Exits:</strong> 
                    <span v-for="(exit, index) in message.scene.exits" :key="index">
                      {{ exit.direction }}<span v-if="exit.description"> ({{ exit.description }})</span><span v-if="index < message.scene.exits.length - 1">, </span>
                    </span>
                  </div>
                </div>
              </div>
              <div v-if="loading" class="loading-indicator">
                <v-progress-circular
                  indeterminate
                  color="primary"
                  size="20"
                  width="2"
                  class="mr-2"
                ></v-progress-circular>
                <span>Thinking...</span>
              </div>
            </div>
          </div>
          
          <div class="game-input-wrapper">
            <v-text-field
              v-model="userInput"
              @keyup.enter="sendCommand"
              placeholder="Enter your command..."
              variant="outlined"
              density="compact"
              hide-details
              class="game-input"
              autofocus
            >
              <template v-slot:append-inner>
                <button
                  @mousedown.prevent="startRecording"
                  @mouseup.prevent="stopRecording"
                  @mouseleave.prevent="stopRecording"
                  @touchstart.prevent="startRecording"
                  @touchend.prevent="stopRecording"
                  @touchcancel.prevent="stopRecording"
                  :disabled="loading"
                  class="audio-record-btn mr-1"
                  :class="{ recording: recording }"
                >
                  <v-icon>mdi-microphone</v-icon>
                </button>
                <v-btn
                  @click="sendCommand"
                  color="primary"
                  variant="text"
                  icon="mdi-send"
                  :disabled="!userInput.trim() || loading"
                  :loading="loading"
                ></v-btn>
              </template>
            </v-text-field>
          </div>
        </div>
      </v-col>
    </v-row>
  </v-container>
</template>

<script lang="ts">
import { defineComponent, ref, nextTick, onMounted, watch } from "vue";
import { useRouter, useRoute } from "vue-router";
import axios from "axios";
import AdventureSetup from "@/components/AdventureSetup.vue";

interface Exit {
  direction: string;
  description?: string;
}

interface Scene {
  locationName: string;
  exits: Exit[];
  items?: string[];
  npcs?: string[];
}

interface GameMessage {
  text: string;
  type: "user" | "game" | "system";
  image?: string | null;
  messageIndex?: number;
  hasImage?: boolean;
  imageLoading?: boolean;
  scene?: Scene;
}

export default defineComponent({
  name: "GameView",
  components: {
    AdventureSetup
  },
  props: {
    gameId: {
      type: String,
      default: null
    }
  },
  setup(props) {
    const router = useRouter();
    const route = useRoute();
    const messages = ref<GameMessage[]>([]);
    const userInput = ref("");
    const gameOutput = ref<HTMLElement>();
    const sessionId = ref<string | null>(null);
    const currentGameId = ref<string | null>(props.gameId || route.params.gameId as string || null);
    const loading = ref(false);
    const recording = ref(false);
    const mediaRecorder = ref<MediaRecorder | null>(null);
    const audioChunks = ref<Blob[]>([]);
    const narrationEnabled = ref(true);
    const narrationVolume = ref(0.8);
    const gameStarted = ref(false);
    const setupStarted = ref(false);
    const adventureTheme = ref<any>(null);
    const artStyle = ref<any>(null);
    const backgroundMusicEnabled = ref(true);
    const backgroundMusicVolume = ref(0.3);
    const currentBackgroundMusic = ref<HTMLAudioElement | null>(null);
    const nextBackgroundMusic = ref<HTMLAudioElement | null>(null);
    const currentMusicMood = ref<string | null>(null);
    const lastMusicData = ref<{ base64: string; mood: string } | null>(null);
    const currentNarration = ref<HTMLAudioElement | null>(null);
    
    // Helper function for logging with timestamps
    const log = (message: string, ...args: any[]) => {
      const timestamp = new Date().toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 });
      console.log(`[${timestamp}] ${message}`, ...args);
    };

    const scrollToBottom = async () => {
      await nextTick();
      if (gameOutput.value) {
        gameOutput.value.scrollTop = gameOutput.value.scrollHeight;
      }
    };

    const beginSetup = () => {
      setupStarted.value = true;
    };
    
    const onAdventureReady = (config: { theme: any, style: any }) => {
      adventureTheme.value = config.theme;
      artStyle.value = config.style;
      gameStarted.value = true;
      setupStarted.value = false;
      nextTick(() => {
        startGame();
      });
    };

    const loadGame = async (gameId: string) => {
      try {
        loading.value = true;
        log(`Loading game: ${gameId}`);
        
        const response = await axios.get(`/api/game/load/${gameId}`);
        
        if (response.data.status === "success") {
          sessionId.value = response.data.sessionId;
          currentGameId.value = response.data.gameId;
          
          // Clear existing messages
          messages.value = [];
          
          // Restore conversation history
          if (response.data.conversationHistory && response.data.conversationHistory.length > 0) {
            response.data.conversationHistory.forEach((entry: any, index: number) => {
              const isLastAssistantMessage = 
                entry.role === "assistant" && 
                index === response.data.conversationHistory.length - 1;
              
              const message: GameMessage = {
                text: entry.content,
                type: entry.role === "user" ? "user" : "game",
                messageIndex: messages.value.length,
                // Add scene info to the last assistant message
                scene: isLastAssistantMessage && response.data.scene ? response.data.scene : undefined,
                hasImage: false,
                imageLoading: false,
                // Add cached image if available for last message
                image: isLastAssistantMessage && response.data.scene?.cachedImage ? 
                  response.data.scene.cachedImage : undefined
              };
              
              // If we have a cached image, mark it as having an image
              if (message.image) {
                message.hasImage = true;
                message.imageLoading = false;
                log(`Loaded cached image for location: ${response.data.scene?.locationId}`);
              }
              
              messages.value.push(message);
            });
            log(`Restored ${response.data.conversationHistory.length} conversation entries`);
          }
          
          // Show current scene info
          if (response.data.scene) {
            const scene = response.data.scene;
            log(`Loaded game at scene: ${scene.locationName} with ${scene.exits?.length || 0} exits`);
          }
          
          gameStarted.value = true;
          loading.value = false;
          
          // Scroll to bottom after loading
          await nextTick();
          await scrollToBottom();
          
          // Update URL if not already there
          if (!route.params.gameId || route.params.gameId !== gameId) {
            router.push(`/game/${gameId}`);
          }
        } else {
          log(`Failed to load game: ${response.data.error}`);
          // Show setup screen if load fails
          loading.value = false;
          setupStarted.value = true;
        }
      } catch (error) {
        log("Error loading game:", error);
        // Show setup screen if load fails
        loading.value = false;
        setupStarted.value = true;
      }
    };
    
    const startGame = async () => {
      try {
        loading.value = true;
        log("Starting game with theme:", adventureTheme.value, "and style:", artStyle.value);
        const response = await axios.post("/api/game/start", {
          theme: adventureTheme.value,
          artStyle: artStyle.value
        });
        log("Game start response:", response.data);
        sessionId.value = response.data.sessionId;
        currentGameId.value = response.data.gameId;
        
        // Update URL to include game ID
        router.push(`/game/${response.data.gameId}`);
        const initialMessage: GameMessage = {
          text: response.data.message,
          type: "game",
          messageIndex: response.data.messageIndex,
          hasImage: response.data.hasImage || false,
          imageLoading: response.data.hasImage || false,
          scene: response.data.scene
        };
        
        messages.value.push(initialMessage);
        const messageIdx = messages.value.length - 1;
        
        // If image is being generated for initial scene, poll for it
        if (response.data.hasImage) {
          log("Initial scene image generation started, polling for result...");
          pollForImage(sessionId.value!, response.data.messageIndex, messageIdx);
        }
        
        // If background music is being generated for initial scene, poll for it
        if (response.data.hasMusic) {
          log("Initial scene music generation started, polling for result...");
          pollForMusic(sessionId.value!, response.data.messageIndex);
        }
        
        // Note: Initial message doesn't have audio as it's hardcoded
        if (response.data.audio) {
          log("Initial message has audio");
          playAudioNarration(response.data.audio);
        } else {
          log("No audio for initial message");
        }
      } catch (error) {
        messages.value.push({
          text: "Error starting game. Please check if the server is running.",
          type: "system",
        });
        log("Error starting game:", error);
      } finally {
        loading.value = false;
      }
    };

    const sendCommand = async () => {
      const command = userInput.value.trim();
      if (!command || !sessionId.value) return;

      // Add user message
      messages.value.push({
        text: `> ${command}`,
        type: "user",
      });

      // Clear input
      userInput.value = "";

      try {
        loading.value = true;
        await scrollToBottom();
        const response = await axios.post("/api/game/command", {
          sessionId: sessionId.value,
          command: command,
        });

        if (response.data.status === "success") {
          log(`[${sessionId.value || 'no-session'}] Command response received:`, response.data);
          const newMessage: GameMessage = {
            text: response.data.response,
            type: "game",
            messageIndex: response.data.messageIndex,
            hasImage: response.data.hasImage || false,
            imageLoading: response.data.hasImage || false,
            scene: response.data.scene
          };
          
          messages.value.push(newMessage);
          const messageIdx = messages.value.length - 1;
          
          // Play audio narration if available
          if (response.data.audio) {
            log(`[${sessionId.value || 'no-session'}] Audio narration available, playing...`);
            playAudioNarration(response.data.audio);
          } else {
            log(`[${sessionId.value || 'no-session'}] No audio narration in response`);
          }
          
          // If image is being generated, poll for it
          if (response.data.hasImage) {
            log(`[${sessionId.value || 'no-session'}] Image generation started, polling for result...`);
            pollForImage(sessionId.value!, response.data.messageIndex, messageIdx);
          }
          
          // If background music is being generated, poll for it
          if (response.data.hasMusic) {
            log(`[${sessionId.value || 'no-session'}] Background music generation started, polling for result...`);
            pollForMusic(sessionId.value!, response.data.messageIndex);
          }
        } else {
          messages.value.push({
            text: `Error: ${response.data.error}`,
            type: "system",
          });
        }
      } catch (error) {
        messages.value.push({
          text: "Error sending command. Please check your connection.",
          type: "system",
        });
        log("Error sending command:", error);
      } finally {
        loading.value = false;
        await scrollToBottom();
      }
    };

    const startRecording = async () => {
      log("Start recording called");
      if (recording.value) {
        log("Already recording, ignoring");
        return;
      }
      
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        
        // Try to use a format that's more compatible with Whisper
        let options = {};
        // Try different formats in order of preference
        const formats = [
          'audio/wav',
          'audio/ogg;codecs=opus',
          'audio/webm;codecs=opus',
          'audio/webm'
        ];
        
        for (const format of formats) {
          if (MediaRecorder.isTypeSupported(format)) {
            options = { mimeType: format };
            log('Using audio format:', format);
            break;
          }
        }
        
        mediaRecorder.value = new MediaRecorder(stream, options);
        audioChunks.value = [];

        mediaRecorder.value.ondataavailable = (event) => {
          if (event.data.size > 0) {
            audioChunks.value.push(event.data);
          }
        };

        mediaRecorder.value.onstop = async () => {
          // Try to use a more compatible audio format
          const mimeType = mediaRecorder.value?.mimeType || "audio/webm";
          const audioBlob = new Blob(audioChunks.value, { type: mimeType });
          log("Recording stopped, mime type:", mimeType, "size:", audioBlob.size);
          await sendAudioCommand(audioBlob);
          
          // Stop all tracks
          stream.getTracks().forEach(track => track.stop());
        };

        mediaRecorder.value.start();
        recording.value = true;
        log("Recording started successfully");
      } catch (error) {
        log("Error accessing microphone:", error);
        messages.value.push({
          text: "Error: Could not access microphone. Please check permissions.",
          type: "system",
        });
      }
    };

    const stopRecording = () => {
      log("Stop recording called", "recording:", recording.value);
      if (mediaRecorder.value && recording.value) {
        mediaRecorder.value.stop();
        recording.value = false;
        log("Recording stopped");
      }
    };

    const sendAudioCommand = async (audioBlob: Blob) => {
      if (!sessionId.value) return;

      // Check if audio clip has content
      log("Audio blob size:", audioBlob.size, "bytes");
      if (audioBlob.size === 0) {
        messages.value.push({
          text: "Please hold the record button to record audio",
          type: "system",
        });
        return;
      }

      // Show user feedback
      messages.value.push({
        text: "> [Audio input - processing...]",
        type: "user",
      });

      try {
        loading.value = true;
        await scrollToBottom();

        // Convert blob to base64
        const reader = new FileReader();
        reader.readAsDataURL(audioBlob);
        const base64Audio = await new Promise<string>((resolve) => {
          reader.onloadend = () => {
            const base64 = reader.result as string;
            // Remove the data:audio/webm;base64, prefix
            resolve(base64.split(',')[1]);
          };
        });

        const response = await axios.post("/api/game/audio", {
          sessionId: sessionId.value,
          audio: base64Audio,
        });

        if (response.data.status === "success") {
          log(`[${sessionId.value || 'no-session'}] Audio command response received:`, response.data);
          
          // Update the last user message to show the transcription
          if (response.data.transcription && messages.value.length > 0) {
            const lastUserMsg = messages.value[messages.value.length - 1];
            if (lastUserMsg.type === "user") {
              lastUserMsg.text = `> ${response.data.transcription}`;
              // Log the transcription for debugging
              log(`[${sessionId.value || 'no-session'}] Transcription result: "${response.data.transcription}"`);
              console.log(`🎤 Voice transcription: "${response.data.transcription}"`);
            }
          }
          
          const newMessage: GameMessage = {
            text: response.data.response,
            type: "game",
            messageIndex: response.data.messageIndex,
            hasImage: response.data.hasImage || false,
            imageLoading: response.data.hasImage || false,
            scene: response.data.scene
          };
          
          messages.value.push(newMessage);
          const messageIdx = messages.value.length - 1;
          
          // Play audio narration if available
          if (response.data.audio) {
            log(`[${sessionId.value || 'no-session'}] Audio narration available from voice command, playing...`);
            playAudioNarration(response.data.audio);
          } else {
            log(`[${sessionId.value || 'no-session'}] No audio narration in voice command response`);
          }
          
          // If image is being generated, poll for it
          if (response.data.hasImage) {
            log(`[${sessionId.value || 'no-session'}] Image generation started from voice command, polling for result...`);
            pollForImage(sessionId.value!, response.data.messageIndex, messageIdx);
          }
          
          // If background music is being generated, poll for it
          if (response.data.hasMusic) {
            log(`[${sessionId.value || 'no-session'}] Background music generation started from voice command, polling for result...`);
            pollForMusic(sessionId.value!, response.data.messageIndex);
          }
        } else {
          messages.value.push({
            text: `Error: ${response.data.error}`,
            type: "system",
          });
        }
      } catch (error) {
        messages.value.push({
          text: "Error processing audio. Please try again.",
          type: "system",
        });
        log("Error sending audio:", error);
      } finally {
        loading.value = false;
        await scrollToBottom();
      }
    };

    const pollForImage = async (sessionId: string, messageIndex: number, arrayIndex: number) => {
      const maxAttempts = 40; // 40 seconds max to account for DALL-E generation time
      let attempts = 0;
      
      const checkImage = async () => {
        try {
          const response = await axios.get(`/api/game/image/${sessionId}/${messageIndex}`);
          
          if (response.data.status === "ready") {
            log(`[${sessionId}] Image ready for message ${arrayIndex}!`);
            // Update the entire message object to ensure Vue detects the change
            const updatedMessage = { ...messages.value[arrayIndex] };
            updatedMessage.image = response.data.image;
            updatedMessage.imageLoading = false;
            messages.value[arrayIndex] = updatedMessage;
            log(`[${sessionId}] Image set for message ${arrayIndex}, base64 length: ${response.data.image.length}`);
          } else if (response.data.status === "failed") {
            log(`[${sessionId}] Image generation failed`);
            const updatedMessage = { ...messages.value[arrayIndex] };
            updatedMessage.imageLoading = false;
            messages.value[arrayIndex] = updatedMessage;
          } else if (response.data.status === "pending" && attempts < maxAttempts) {
            attempts++;
            setTimeout(checkImage, 1000); // Check again in 1 second
          } else {
            log(`[${sessionId}] Image generation timed out`);
            messages.value[arrayIndex].imageLoading = false;
          }
        } catch (error) {
          log("Error polling for image:", error);
          messages.value[arrayIndex].imageLoading = false;
        }
      };
      
      // Start polling after a short delay
      setTimeout(checkImage, 500);
    };

    const playAudioNarration = (audioBase64: string) => {
      log(`[${sessionId.value || 'no-session'}] playAudioNarration called, narration enabled:`, narrationEnabled.value);
      log(`[${sessionId.value || 'no-session'}] Audio base64 length:`, audioBase64.length);
      
      if (!narrationEnabled.value) {
        log(`[${sessionId.value || 'no-session'}] Narration is disabled, skipping playback`);
        return;
      }
      
      try {
        // Stop any current narration
        if (currentNarration.value && !currentNarration.value.paused) {
          currentNarration.value.pause();
          currentNarration.value = null;
        }
        
        // Create audio element
        const audioUrl = `data:audio/mp3;base64,${audioBase64}`;
        log(`[${sessionId.value || 'no-session'}] Creating audio element with URL length:`, audioUrl.length);
        
        const audio = new Audio(audioUrl);
        audio.volume = narrationVolume.value;
        currentNarration.value = audio;
        
        // Add event listeners for debugging
        audio.addEventListener('loadeddata', () => {
          log(`[${sessionId.value || 'no-session'}] Audio loaded successfully, duration:`, audio.duration);
        });
        
        audio.addEventListener('error', (e) => {
          log(`[${sessionId.value || 'no-session'}] Audio error event:`, e);
          log(`[${sessionId.value || 'no-session'}] Audio error details:`, audio.error);
        });
        
        audio.addEventListener('play', () => {
          log(`[${sessionId.value || 'no-session'}] Audio started playing`);
        });
        
        audio.addEventListener('ended', () => {
          log(`[${sessionId.value || 'no-session'}] Audio playback ended`);
          if (currentNarration.value === audio) {
            currentNarration.value = null;
          }
        });
        
        // Play the audio
        log(`[${sessionId.value || 'no-session'}] Attempting to play audio...`);
        audio.play()
          .then(() => {
            log(`[${sessionId.value || 'no-session'}] Audio play() promise resolved successfully`);
          })
          .catch(error => {
            log(`[${sessionId.value || 'no-session'}] Error playing audio narration:`, error);
            log(`[${sessionId.value || 'no-session'}] Error type:`, error.name, "Message:", error.message);
          });
        
        log(`[${sessionId.value || 'no-session'}] Playing audio narration at volume:`, narrationVolume.value);
      } catch (error) {
        log(`[${sessionId.value || 'no-session'}] Error creating audio element:`, error);
        log(`[${sessionId.value || 'no-session'}] Error details:`, error);
      }
    };

    const pollForMusic = async (sessionId: string, messageIndex: number) => {
      const maxAttempts = 60; // 60 seconds max for music generation
      let attempts = 0;
      
      const checkMusic = async () => {
        try {
          const response = await axios.get(`/api/game/music/${sessionId}/${messageIndex}`);
          
          if (response.data.status === "ready") {
            log(`[${sessionId}] Background music ready for message ${messageIndex}, mood: ${response.data.mood}`);
            playBackgroundMusic(response.data.music, response.data.mood);
          } else if (response.data.status === "failed") {
            log(`[${sessionId}] Background music generation failed`);
          } else if (response.data.status === "pending" && attempts < maxAttempts) {
            attempts++;
            setTimeout(checkMusic, 1000); // Check again in 1 second
          } else {
            log(`[${sessionId}] Background music generation timed out`);
          }
        } catch (error) {
          log("Error polling for music:", error);
        }
      };
      
      // Start polling after a short delay
      setTimeout(checkMusic, 1000);
    };

    const playBackgroundMusic = (musicBase64: string, mood: string) => {
      log(`Playing background music, mood: ${mood}, enabled: ${backgroundMusicEnabled.value}`);
      
      // Store the music data for potential resume
      lastMusicData.value = { base64: musicBase64, mood: mood };
      
      if (!backgroundMusicEnabled.value) {
        log("Background music is disabled, storing for later");
        return;
      }
      
      try {
        // Create new audio element for the new music
        const audioUrl = `data:audio/mp3;base64,${musicBase64}`;
        const newMusic = new Audio(audioUrl);
        newMusic.volume = 0; // Start at 0 for fade-in
        newMusic.loop = true; // Loop background music
        
        // If there's current music playing, crossfade
        if (currentBackgroundMusic.value && !currentBackgroundMusic.value.paused) {
          log("Crossfading from current music to new music");
          
          // Fade out current music
          const fadeOutInterval = setInterval(() => {
            if (currentBackgroundMusic.value && currentBackgroundMusic.value.volume > 0.01) {
              currentBackgroundMusic.value.volume = Math.max(0, currentBackgroundMusic.value.volume - 0.02);
            } else {
              clearInterval(fadeOutInterval);
              if (currentBackgroundMusic.value) {
                currentBackgroundMusic.value.pause();
                currentBackgroundMusic.value = null;
              }
            }
          }, 50);
          
          // Start playing new music and fade in
          newMusic.play().then(() => {
            const fadeInInterval = setInterval(() => {
              if (newMusic.volume < backgroundMusicVolume.value) {
                newMusic.volume = Math.min(backgroundMusicVolume.value, newMusic.volume + 0.02);
              } else {
                clearInterval(fadeInInterval);
              }
            }, 50);
          }).catch(error => {
            log("Error playing background music:", error);
          });
        } else {
          // No current music, just fade in the new music
          newMusic.play().then(() => {
            log("Starting new background music");
            const fadeInInterval = setInterval(() => {
              if (newMusic.volume < backgroundMusicVolume.value) {
                newMusic.volume = Math.min(backgroundMusicVolume.value, newMusic.volume + 0.02);
              } else {
                clearInterval(fadeInInterval);
              }
            }, 50);
          }).catch(error => {
            log("Error playing background music:", error);
          });
        }
        
        currentBackgroundMusic.value = newMusic;
        currentMusicMood.value = mood;
        
      } catch (error) {
        log("Error creating background music element:", error);
      }
    };

    // Watch for background music volume changes
    watch(backgroundMusicVolume, (newVolume) => {
      if (currentBackgroundMusic.value && !currentBackgroundMusic.value.paused) {
        currentBackgroundMusic.value.volume = newVolume;
      }
    });

    // Watch for narration volume changes
    watch(narrationVolume, (newVolume) => {
      if (currentNarration.value && !currentNarration.value.paused) {
        currentNarration.value.volume = newVolume;
      }
    });

    // Watch for background music enabled changes
    watch(backgroundMusicEnabled, (enabled) => {
      if (!enabled && currentBackgroundMusic.value) {
        log("Disabling background music");
        // Pause music but keep the reference
        if (currentBackgroundMusic.value) {
          // Fade out for smoother experience
          const musicToStop = currentBackgroundMusic.value;
          const fadeOutInterval = setInterval(() => {
            if (musicToStop.volume > 0.01) {
              musicToStop.volume = Math.max(0, musicToStop.volume - 0.05);
            } else {
              clearInterval(fadeOutInterval);
              musicToStop.pause();
              log("Background music paused");
            }
          }, 20);
        }
      } else if (enabled) {
        log("Background music re-enabled");
        // Resume current music if it exists and is paused
        if (currentBackgroundMusic.value && currentBackgroundMusic.value.paused) {
          log("Resuming paused background music");
          currentBackgroundMusic.value.volume = 0;
          currentBackgroundMusic.value.play().then(() => {
            // Fade in
            const fadeInInterval = setInterval(() => {
              if (currentBackgroundMusic.value && currentBackgroundMusic.value.volume < backgroundMusicVolume.value) {
                currentBackgroundMusic.value.volume = Math.min(backgroundMusicVolume.value, currentBackgroundMusic.value.volume + 0.02);
              } else {
                clearInterval(fadeInInterval);
              }
            }, 50);
          }).catch(error => {
            log("Error resuming background music:", error);
          });
        } else if (lastMusicData.value) {
          // If no current music but we have last music data, play it
          log("Playing last known background music");
          playBackgroundMusic(lastMusicData.value.base64, lastMusicData.value.mood);
        }
      }
    });

    // Watch for narration enabled changes
    watch(narrationEnabled, (enabled) => {
      if (!enabled && currentNarration.value && !currentNarration.value.paused) {
        // Stop current narration immediately
        log("Stopping current narration as narration was disabled");
        currentNarration.value.pause();
        currentNarration.value.currentTime = 0;
        currentNarration.value = null;
      }
    });

    const saveGame = async () => {
      if (!sessionId.value) {
        log("No session to save");
        return;
      }
      
      try {
        const response = await axios.post(`/api/game/save/${sessionId.value}`, {});
        if (response.data.status === "success") {
          messages.value.push({
            text: "Game saved successfully!",
            type: "system"
          });
          log(`Game saved: ${response.data.gameId}`);
        } else {
          messages.value.push({
            text: `Failed to save game: ${response.data.error}`,
            type: "system"
          });
        }
      } catch (error) {
        log("Error saving game:", error);
        messages.value.push({
          text: "Error saving game",
          type: "system"
        });
      }
    };

    onMounted(() => {
      // Check if there's a game ID in the URL
      const gameIdFromRoute = route.params.gameId as string;
      if (gameIdFromRoute) {
        log(`Found game ID in URL: ${gameIdFromRoute}`);
        loadGame(gameIdFromRoute);
      } else {
        // No game ID, show setup screen
        log("No game ID in URL, showing setup screen");
      }
    });

    return {
      messages,
      userInput,
      gameOutput,
      sendCommand,
      loading,
      recording,
      startRecording,
      stopRecording,
      narrationEnabled,
      narrationVolume,
      pollForImage,
      gameStarted,
      setupStarted,
      beginSetup,
      onAdventureReady,
      backgroundMusicEnabled,
      backgroundMusicVolume,
      currentMusicMood,
      pollForMusic,
      loadGame,
      saveGame,
      currentGameId,
    };
  },
});
</script>

<style scoped>
.intro-screen {
  width: 100%;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0d0d0d 0%, #1a1a2e 100%);
  position: relative;
  overflow: hidden;
}

.intro-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3rem;
  animation: fadeIn 1s ease-in;
}

.intro-image {
  max-width: 90%;
  max-height: 70vh;
  object-fit: contain;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.8);
}

.begin-button {
  font-size: 1.2rem;
  padding: 1.5rem 3rem;
  text-transform: uppercase;
  letter-spacing: 2px;
  animation: pulse 2s ease-in-out infinite;
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

.game-container {
  height: 100vh;
  background-color: #121212;
  display: flex;
}

.game-row {
  flex: 1;
  display: flex;
}

.game-col {
  display: flex;
}

.game-wrapper {
  width: 100%;
  display: flex;
  flex-direction: column;
  height: 100vh;
  padding: 1rem;
}

.game-title {
  flex-shrink: 0;
  padding: 1rem;
  background-color: #1a1a1a;
  border-bottom: 1px solid #333;
}

.game-output-wrapper {
  flex: 1;
  overflow: hidden;
  background-color: #1e1e1e;
  border-radius: 4px;
  margin-bottom: 1rem;
}

.game-output {
  height: 100%;
  overflow-y: auto;
  font-family: "Courier New", Courier, monospace;
  padding: 1rem;
}

.game-input-wrapper {
  flex-shrink: 0;
  background-color: #121212;
  padding: 0.5rem 0;
}

.game-message {
  margin-bottom: 0.5rem;
  line-height: 1.6;
  display: flex;
  align-items: flex-start;
  gap: 1rem;
}

.game-message.user {
  color: #64b5f6;
}

.game-message.game {
  color: #81c784;
}

.game-message.system {
  color: #ffb74d;
  font-style: italic;
}

.exits-info {
  margin-top: 0.5rem;
  padding-top: 0.5rem;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  color: #4CAF50;
  font-size: 0.95em;
}

.game-input {
  font-family: "Courier New", Courier, monospace;
  background-color: #1e1e1e !important;
}

/* Custom scrollbar for game output */
.game-output::-webkit-scrollbar {
  width: 8px;
}

.game-output::-webkit-scrollbar-track {
  background: #0d0d0d;
}

.game-output::-webkit-scrollbar-thumb {
  background: #555;
  border-radius: 4px;
}

.game-output::-webkit-scrollbar-thumb:hover {
  background: #777;
}

.loading-indicator {
  display: flex;
  align-items: center;
  color: #9e9e9e;
  font-style: italic;
  margin-top: 0.5rem;
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% {
    opacity: 0.6;
  }
  50% {
    opacity: 1;
  }
}

.audio-record-btn {
  background: transparent;
  border: none;
  padding: 8px;
  cursor: pointer;
  border-radius: 50%;
  transition: all 0.2s;
  color: #f44336;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.audio-record-btn:hover:not(:disabled) {
  background-color: rgba(244, 67, 54, 0.1);
}

.audio-record-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.audio-record-btn.recording {
  background-color: #f44336;
  color: white;
  animation: recording-pulse 1s ease-in-out infinite;
}

@keyframes recording-pulse {
  0% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.8;
    transform: scale(1.1);
  }
  100% {
    opacity: 1;
    transform: scale(1);
  }
}

.audio-controls {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.scene-image-container {
  flex-shrink: 0;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
  width: 256px;
  height: 256px;
  order: -1; /* Places image on the left */
}

.scene-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  border-radius: 8px;
}

.message-text {
  flex: 1;
}

.image-loading {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: rgba(255, 255, 255, 0.05);
}
</style>
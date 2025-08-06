<template>
  <v-container fluid class="game-container pa-0">
    <!-- Intro Screen -->
    <div v-if="!gameStarted" class="intro-screen">
      <div class="intro-content">
        <img src="/SZork_intro.webp" alt="Welcome to Szork" class="intro-image" />
        <v-btn 
          color="primary" 
          size="x-large" 
          class="begin-button"
          @click="beginAdventure"
          elevation="8"
        >
          Begin Your Adventure
        </v-btn>
      </div>
    </div>
    
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
                <div class="message-text">{{ message.text }}</div>
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
import { defineComponent, ref, nextTick, onMounted } from "vue";
import axios from "axios";

interface GameMessage {
  text: string;
  type: "user" | "game" | "system";
  image?: string | null;
  messageIndex?: number;
  hasImage?: boolean;
  imageLoading?: boolean;
}

export default defineComponent({
  name: "GameView",
  setup() {
    const messages = ref<GameMessage[]>([]);
    const userInput = ref("");
    const gameOutput = ref<HTMLElement>();
    const sessionId = ref<string | null>(null);
    const loading = ref(false);
    const recording = ref(false);
    const mediaRecorder = ref<MediaRecorder | null>(null);
    const audioChunks = ref<Blob[]>([]);
    const narrationEnabled = ref(true);
    const narrationVolume = ref(0.8);
    const gameStarted = ref(false);
    
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

    const beginAdventure = () => {
      gameStarted.value = true;
      nextTick(() => {
        startGame();
      });
    };

    const startGame = async () => {
      try {
        loading.value = true;
        log("Starting game...");
        const response = await axios.post("/api/game/start");
        log("Game start response:", response.data);
        sessionId.value = response.data.sessionId;
        const initialMessage: GameMessage = {
          text: response.data.message,
          type: "game",
          messageIndex: response.data.messageIndex,
          hasImage: response.data.hasImage || false,
          imageLoading: response.data.hasImage || false
        };
        
        messages.value.push(initialMessage);
        const messageIdx = messages.value.length - 1;
        
        // If image is being generated for initial scene, poll for it
        if (response.data.hasImage) {
          log("Initial scene image generation started, polling for result...");
          pollForImage(sessionId.value!, response.data.messageIndex, messageIdx);
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
            imageLoading: response.data.hasImage || false
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

      // Show user feedback
      messages.value.push({
        text: "> [Audio input]",
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
            }
          }
          
          const newMessage: GameMessage = {
            text: response.data.response,
            type: "game",
            messageIndex: response.data.messageIndex,
            hasImage: response.data.hasImage || false,
            imageLoading: response.data.hasImage || false
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
        // Create audio element
        const audioUrl = `data:audio/mp3;base64,${audioBase64}`;
        log(`[${sessionId.value || 'no-session'}] Creating audio element with URL length:`, audioUrl.length);
        
        const audio = new Audio(audioUrl);
        audio.volume = narrationVolume.value;
        
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

    onMounted(() => {
      // Game now starts when user clicks "Begin Your Adventure"
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
      beginAdventure,
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
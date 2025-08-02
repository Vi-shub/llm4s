<template>
  <v-container fluid class="game-container pa-0">
    <v-row class="game-row ma-0">
      <v-col cols="12" md="8" class="mx-auto game-col pa-0">
        <div class="game-wrapper">
          <v-card-title class="text-h4 text-center game-title">
            <v-row align="center" justify="space-between">
              <v-col cols="3">
                <div class="audio-controls">
                  <v-switch
                    v-model="narrationEnabled"
                    label="Narration"
                    density="compact"
                    hide-details
                  ></v-switch>
                </div>
              </v-col>
              <v-col cols="6" class="text-center">
                Welcome to Szork
              </v-col>
              <v-col cols="3"></v-col>
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
                <div v-if="message.image" class="scene-image-container">
                  <img 
                    :src="`data:image/png;base64,${message.image}`" 
                    alt="Scene visualization"
                    class="scene-image"
                  />
                </div>
                {{ message.text }}
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

    const scrollToBottom = async () => {
      await nextTick();
      if (gameOutput.value) {
        gameOutput.value.scrollTop = gameOutput.value.scrollHeight;
      }
    };

    const startGame = async () => {
      try {
        loading.value = true;
        console.log("Starting game...");
        const response = await axios.post("/api/game/start");
        console.log("Game start response:", response.data);
        sessionId.value = response.data.sessionId;
        messages.value.push({
          text: response.data.message,
          type: "game",
        });
        
        // Note: Initial message doesn't have audio as it's hardcoded
        if (response.data.audio) {
          console.log("Initial message has audio");
          playAudioNarration(response.data.audio);
        } else {
          console.log("No audio for initial message");
        }
      } catch (error) {
        messages.value.push({
          text: "Error starting game. Please check if the server is running.",
          type: "system",
        });
        console.error("Error starting game:", error);
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
          console.log("Command response received:", response.data);
          messages.value.push({
            text: response.data.response,
            type: "game",
            image: response.data.image || null
          });
          
          // Play audio narration if available
          if (response.data.audio) {
            console.log("Audio narration available, playing...");
            playAudioNarration(response.data.audio);
          } else {
            console.log("No audio narration in response");
          }
          
          // Log if image is available
          if (response.data.image) {
            console.log("Scene image available");
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
        console.error("Error sending command:", error);
      } finally {
        loading.value = false;
        await scrollToBottom();
      }
    };

    const startRecording = async () => {
      console.log("Start recording called");
      if (recording.value) {
        console.log("Already recording, ignoring");
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
            console.log('Using audio format:', format);
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
          console.log("Recording stopped, mime type:", mimeType, "size:", audioBlob.size);
          await sendAudioCommand(audioBlob);
          
          // Stop all tracks
          stream.getTracks().forEach(track => track.stop());
        };

        mediaRecorder.value.start();
        recording.value = true;
        console.log("Recording started successfully");
      } catch (error) {
        console.error("Error accessing microphone:", error);
        messages.value.push({
          text: "Error: Could not access microphone. Please check permissions.",
          type: "system",
        });
      }
    };

    const stopRecording = () => {
      console.log("Stop recording called", "recording:", recording.value);
      if (mediaRecorder.value && recording.value) {
        mediaRecorder.value.stop();
        recording.value = false;
        console.log("Recording stopped");
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
          console.log("Audio command response received:", response.data);
          
          // Update the last user message to show the transcription
          if (response.data.transcription && messages.value.length > 0) {
            const lastUserMsg = messages.value[messages.value.length - 1];
            if (lastUserMsg.type === "user") {
              lastUserMsg.text = `> ${response.data.transcription}`;
            }
          }
          
          messages.value.push({
            text: response.data.response,
            type: "game",
            image: response.data.image || null
          });
          
          // Play audio narration if available
          if (response.data.audio) {
            console.log("Audio narration available from voice command, playing...");
            playAudioNarration(response.data.audio);
          } else {
            console.log("No audio narration in voice command response");
          }
          
          // Log if image is available
          if (response.data.image) {
            console.log("Scene image available from voice command");
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
        console.error("Error sending audio:", error);
      } finally {
        loading.value = false;
        await scrollToBottom();
      }
    };

    const playAudioNarration = (audioBase64: string) => {
      console.log("playAudioNarration called, narration enabled:", narrationEnabled.value);
      console.log("Audio base64 length:", audioBase64.length);
      
      if (!narrationEnabled.value) {
        console.log("Narration is disabled, skipping playback");
        return;
      }
      
      try {
        // Create audio element
        const audioUrl = `data:audio/mp3;base64,${audioBase64}`;
        console.log("Creating audio element with URL length:", audioUrl.length);
        
        const audio = new Audio(audioUrl);
        audio.volume = narrationVolume.value;
        
        // Add event listeners for debugging
        audio.addEventListener('loadeddata', () => {
          console.log("Audio loaded successfully, duration:", audio.duration);
        });
        
        audio.addEventListener('error', (e) => {
          console.error("Audio error event:", e);
          console.error("Audio error details:", audio.error);
        });
        
        audio.addEventListener('play', () => {
          console.log("Audio started playing");
        });
        
        audio.addEventListener('ended', () => {
          console.log("Audio playback ended");
        });
        
        // Play the audio
        console.log("Attempting to play audio...");
        audio.play()
          .then(() => {
            console.log("Audio play() promise resolved successfully");
          })
          .catch(error => {
            console.error("Error playing audio narration:", error);
            console.error("Error type:", error.name, "Message:", error.message);
          });
        
        console.log("Playing audio narration at volume:", narrationVolume.value);
      } catch (error) {
        console.error("Error creating audio element:", error);
        console.error("Error details:", error);
      }
    };

    onMounted(() => {
      startGame();
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
    };
  },
});
</script>

<style scoped>
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
  padding: 1rem 0 2rem 0;
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
  font-size: 0.875rem;
}

.scene-image-container {
  margin-bottom: 1rem;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}

.scene-image {
  width: 100%;
  max-width: 600px;
  height: auto;
  display: block;
  border-radius: 8px;
}
</style>
<template>
  <v-container fluid class="game-container pa-0">
    <v-row class="game-row ma-0">
      <v-col cols="12" md="8" class="mx-auto game-col pa-0">
        <div class="game-wrapper">
          <v-card-title class="text-h4 text-center game-title">
            Welcome to Szork
          </v-card-title>
          
          <div class="game-output-wrapper">
            <div class="game-output" ref="gameOutput">
              <div
                v-for="(message, index) in messages"
                :key="index"
                class="game-message"
                :class="message.type"
              >
                {{ message.text }}
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
}

export default defineComponent({
  name: "GameView",
  setup() {
    const messages = ref<GameMessage[]>([]);
    const userInput = ref("");
    const gameOutput = ref<HTMLElement>();
    const sessionId = ref<string | null>(null);
    const loading = ref(false);

    const scrollToBottom = async () => {
      await nextTick();
      if (gameOutput.value) {
        gameOutput.value.scrollTop = gameOutput.value.scrollHeight;
      }
    };

    const startGame = async () => {
      try {
        loading.value = true;
        const response = await axios.post("/api/game/start");
        sessionId.value = response.data.sessionId;
        messages.value.push({
          text: response.data.message,
          type: "game",
        });
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
        const response = await axios.post("/api/game/command", {
          sessionId: sessionId.value,
          command: command,
        });

        if (response.data.status === "success") {
          messages.value.push({
            text: response.data.response,
            type: "game",
          });
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

    onMounted(() => {
      startGame();
    });

    return {
      messages,
      userInput,
      gameOutput,
      sendCommand,
      loading,
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
</style>
import { useEffect, useRef, useState } from 'react'

const WS_URL = import.meta.env.VITE_WS_URL || 'ws://localhost:8080/ws/live'

export function useLiveSocket(onMessage) {
  const [connected, setConnected] = useState(false)
  const callbackRef = useRef(onMessage)
  callbackRef.current = onMessage

  useEffect(() => {
    let socket
    let retryDelay = 1000
    let closedByEffect = false
    let retryTimer

    function connect() {
      socket = new WebSocket(WS_URL)

      socket.onopen = () => {
        setConnected(true)
        retryDelay = 1000
      }

      socket.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)
          callbackRef.current?.(data)
        } catch (e) {
          console.warn('Malformed live message', e)
        }
      }

      socket.onclose = () => {
        setConnected(false)
        if (!closedByEffect) {
          retryTimer = setTimeout(connect, retryDelay)
          retryDelay = Math.min(retryDelay * 1.5, 10000)
        }
      }

      socket.onerror = () => {
        socket.close()
      }
    }

    connect()

    return () => {
      closedByEffect = true
      clearTimeout(retryTimer)
      socket?.close()
    }
  }, [])

  return { connected }
}

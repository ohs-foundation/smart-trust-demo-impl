import jsQR from "jsqr";
import { useEffect, useRef, useState } from "react";

export function Scanner({ onText }: { onText: (text: string) => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const [failure, setFailure] = useState<string>();

  useEffect(() => {
    let stopped = false;
    let stream: MediaStream | undefined;
    const canvas = document.createElement("canvas");
    const look = () => {
      const frame = video.current;
      if (stopped || !frame) return;
      if (frame.videoWidth > 0) {
        canvas.width = frame.videoWidth;
        canvas.height = frame.videoHeight;
        const context = canvas.getContext("2d", { willReadFrequently: true });
        context?.drawImage(frame, 0, 0);
        const image = context?.getImageData(0, 0, canvas.width, canvas.height);
        const found = image && jsQR(image.data, image.width, image.height);
        if (found) {
          onText(found.data);
          return;
        }
      }
      requestAnimationFrame(look);
    };
    void (async () => {
      if (!navigator.mediaDevices) throw new Error("This page cannot use the camera; paste the link instead.");
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } });
      if (stopped) return stream.getTracks().forEach((track) => track.stop());
      if (video.current) {
        video.current.srcObject = stream;
        await video.current.play();
      }
      look();
    })().catch((error: unknown) => setFailure(error instanceof Error ? error.message : String(error)));
    return () => {
      stopped = true;
      stream?.getTracks().forEach((track) => track.stop());
    };
  }, [onText]);

  if (failure) return <p role="alert">{failure}</p>;
  return <video ref={video} muted playsInline style={{ maxWidth: "100%", width: 320 }} />;
}

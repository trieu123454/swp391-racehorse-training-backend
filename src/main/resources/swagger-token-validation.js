// Swagger UI stores HTTP bearer values without checking them. Verify with the API first.
function SwaggerTokenValidationPlugin() {
  return {
    statePlugins: {
      auth: {
        wrapActions: {
          authorizeWithPersistOption: (originalAction, system) => (payload) => {
            const auth = payload && payload.bearerAuth;
            const token = auth && typeof auth.value === "string" ? auth.value.trim() : "";
            const authId = "bearerAuth";
            system.errActions.clear({ authId, type: "auth", source: "auth" });

            const showError = (message) => system.errActions.newAuthErr({
              authId, type: "auth", source: "auth", level: "error", message
            });
            if (!token || token.startsWith("Bearer ")) {
              showError("Nhập accessToken từ /api/auth/login, không nhập email hoặc chữ Bearer.");
              return;
            }

            fetch("/api/auth/me", {
              headers: { Authorization: `Bearer ${token}` },
              cache: "no-store"
            }).then((response) => {
              if (!response.ok) {
                showError("Token không hợp lệ hoặc đã hết hạn. Hãy đăng nhập để lấy accessToken mới.");
                return;
              }
              return originalAction({ ...payload, bearerAuth: { ...auth, value: token } });
            }).catch(() => showError("Không thể kiểm tra token với máy chủ. Vui lòng thử lại."));
          }
        }
      }
    }
  };
}

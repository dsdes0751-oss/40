import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { GoogleGenerativeAI } from "@google/generative-ai";

admin.initializeApp();
const db = admin.firestore();

const VN_CHAR_MAX = 100000;
const VN_INPUT_CHAR_LIMIT = 90;

type VnPackageId = "SMALL" | "MEDIUM" | "LARGE";

const VN_PACKAGES: Record<VnPackageId, { addChars: number; costSilver: number }> = {
    SMALL: { addChars: 5000, costSilver: 50 },
    MEDIUM: { addChars: 10000, costSilver: 80 },
    LARGE: { addChars: 50000, costSilver: 350 },
};

function normalizeDeeplCode(input: unknown): "KO" | "EN" | "JA" | "ZH" {
    const code = String(input || "").trim().toUpperCase();
    if (code === "KO" || code === "EN" || code === "JA" || code === "ZH") {
        return code;
    }
    throw new HttpsError("invalid-argument", "Unsupported language code.");
}

function chunkArray<T>(array: T[], size: number): T[][] {
    const chunked: T[][] = [];
    for (let i = 0; i < array.length; i += size) {
        chunked.push(array.slice(i, i + size));
    }
    return chunked;
}

export const translateManga = onCall({
    region: "asia-northeast3",
    secrets: ["GEMINI_API_KEY"],
    memory: "512MiB",
    timeoutSeconds: 300
}, async (request) => {
    const { data, auth } = request;

    if (!auth) {
        throw new HttpsError("unauthenticated", "로그인이 필요합니다.");
    }

    const uid = auth.uid;
    const email = auth.token.email || "";
    const requests = data.requests;
    const imageCount = data.imageCount || 1;
    const serviceType = data.serviceType || "MANGA";
    const modelTier = data.modelTier || "ADVANCED";
    const targetLang = data.targetLang || "Korean";

    if (!requests || !Array.isArray(requests)) {
        throw new HttpsError("invalid-argument", "요청 데이터가 올바르지 않습니다.");
    }

    const cost = imageCount;
    const startTime = Date.now();

    let txDescription = "";
    if (serviceType === "SCREEN") {
        txDescription = "Screen Translation";
    } else {
        txDescription = `Manga Translation (${cost} pages)`;
    }

    let requiredCurrency = "current_balance";
    let aiModelName = "gemini-2.5-flash-lite";
    let currencyName = "Silver";

    // [수정] 복잡한 변수들 제거하고 config 객체 하나로 통합 준비
    const generationConfig: any = {
        temperature: 0.3,
        responseMimeType: "text/plain"
    };

    // [설정] 등급별 모델 및 설정값 주입
    if (modelTier === "PRO") {
        requiredCurrency = "gold_balance";
        aiModelName = "gemini-3-flash-preview";
        currencyName = "Gold";

        // [수정] generationConfig에 직접 thinkingConfig 추가
        generationConfig.thinkingConfig = {
            includeThoughts: false,
            thinkingLevel: "low"
        };

    } else if (modelTier === "ADVANCED") {
        // defaults (gemini-2.5-flash-lite)
    } else {
        throw new HttpsError("invalid-argument", "Standard tier uses on-device translation.");
    }

    // 1. 포인트 차감 트랜잭션
    try {
        await db.runTransaction(async (transaction) => {
            const userRef = db.collection("users").doc(uid);
            const userDoc = await transaction.get(userRef);

            if (!userDoc.exists) throw new HttpsError("not-found", "지갑 없음");

            const balance = userDoc.data()?.[requiredCurrency] || 0;

            if (balance < cost) {
                throw new HttpsError("resource-exhausted", `${currencyName}가 부족합니다.`);
            }

            const newBalance = balance - cost;
            transaction.update(userRef, { [requiredCurrency]: newBalance });

            const txRef = userRef.collection("transactions").doc();
            transaction.set(txRef, {
                uid,
                type: "USE",
                amount: -cost,
                balance_snapshot: newBalance,
                currency: currencyName,
                description: `${txDescription} [${modelTier}]`,
                timestamp: admin.firestore.FieldValue.serverTimestamp()
            });
        });
    } catch (paymentError: any) {
        console.error("Payment Failed:", paymentError);
        throw paymentError;
    }

    // 2. AI 번역 요청
    try {
        const apiKey = process.env.GEMINI_API_KEY;
        if (!apiKey) throw new Error("API Key Missing");

        const genAI = new GoogleGenerativeAI(apiKey);

        // 모델 초기화
        const model = genAI.getGenerativeModel({
            model: aiModelName,
        });

        const BATCH_SIZE = 40;
        const requestBatches = chunkArray(requests, BATCH_SIZE);

        console.log(`[작업 시작] 타입: ${serviceType}, 등급: ${modelTier}, 모델: ${aiModelName}, Thinking: ${modelTier === "PRO" ? "LOW" : "Off"}`);

        const batchPromises = requestBatches.map(async (batch) => {
            let promptBlock = "";
            batch.forEach((req: any) => {
                const safeId = String(req.id).replace(/\//g, "-");
                const cleanText = String(req.text)
                    .replace(/\n/g, " ")
                    .replace(/\//g, " ")
                    .trim();
                promptBlock += `${safeId}/${cleanText}\n`;
            });

            // [수정됨] Template Literal 내부의 백틱(`)을 역슬래시(\)로 이스케이프 처리함
            const instruction = `
            <role>
            You are an expert Manga Translator specializing in correcting OCR errors and translating text into natural, context-aware ${targetLang}.
            </role>

            <instructions>
            1. **OCR Correction**: The input text often contains scanning errors, noise, or broken characters. You must infer the original meaning based on phonetic similarity and manga context before translating.
            2. **Natural Translation**: Use a conversational, spoken ${targetLang} tone appropriate for manga dialogue. Avoid literal machine translation.
            3. **SFX Handling**: If a line is purely Sound Effects (SFX/Onomatopoeia), return an empty string after the slash.
            4. **Strict Output**: Output **ONLY** the ${targetLang} translation. Never include the original text, notes, or explanations.
            </instructions>

            <formatting_rules>
            - Output format: \`{ID}/{TranslatedText}\`
            - Maintain the exact \`{ID}\` provided in the input.
            - Do not add markdown code blocks or extra whitespace.
            </formatting_rules>

            <examples>
            Input: 10/な1_に?
            Output: 10/{natural ${targetLang} translation}

            Input: 12/ドカッ (SFX)
            Output: 12/
            </examples>

            <task>
            Translate the following lines to ${targetLang} immediately according to the rules above.
            </task>

            input:

            `;
            const fullPrompt = instruction + promptBlock;

            // [수정] 미리 만들어둔 generationConfig를 그대로 사용
            const result = await model.generateContent({
                contents: [{ role: "user", parts: [{ text: fullPrompt }] }],
                generationConfig: generationConfig
            });

            const response = await result.response;
            let rawText = response.text().trim();
            rawText = rawText.replace(/^```.*\n/g, "").replace(/```$/g, "").trim();

            return { rawText, usage: response.usageMetadata };
        });

        const results = await Promise.all(batchPromises);

        let combinedRawText = "";
        let totalPromptTokens = 0;
        let totalCandidateTokens = 0;
        let totalTotalTokens = 0;

        results.forEach(res => {
            combinedRawText += res.rawText + "\n";
            if (res.usage) {
                totalPromptTokens += res.usage.promptTokenCount || 0;
                totalCandidateTokens += res.usage.candidatesTokenCount || 0;
                totalTotalTokens += res.usage.totalTokenCount || 0;
            }
        });

        let parsedResults: any[] = [];

        try {
            const lines = combinedRawText.split('\n');
            lines.forEach(line => {
                line = line.trim();
                if (!line) return;
                const match = line.match(/^([^/]+)\s*\/\s*(.*)$/);
                if (match) {
                    const idStr = match[1].trim();
                    const text = match[2].trim();
                    const numId = Number(idStr);
                    const finalId = isNaN(numId) ? idStr : numId;
                    parsedResults.push({ id: finalId, text: text });
                }
            });

            if (parsedResults.length === 0 && requests.length > 0) {
                console.error("원본 응답:", combinedRawText);
                throw new Error("번역 결과 파싱 실패 (결과 없음)");
            }

        } catch (e: any) {
            console.error("Parsing Failed:", e);
            throw new Error(`AI 응답 파싱 오류: ${e.message}`);
        }

        const duration = Date.now() - startTime;

        const userUpdateData: any = {
            email: email,
            total_translated_pages: admin.firestore.FieldValue.increment(cost),
            total_tokens: admin.firestore.FieldValue.increment(totalTotalTokens),
            total_input_tokens: admin.firestore.FieldValue.increment(totalPromptTokens),
            total_output_tokens: admin.firestore.FieldValue.increment(totalCandidateTokens),
            last_active_at: admin.firestore.FieldValue.serverTimestamp()
        };

        if (currencyName === "Gold") {
            userUpdateData.total_used_gold = admin.firestore.FieldValue.increment(cost);
        } else {
            userUpdateData.total_used_silver = admin.firestore.FieldValue.increment(cost);
        }

        await db.collection("users").doc(uid).set(userUpdateData, { merge: true });

        return {
            success: true,
            results: parsedResults,
            cost: cost,
            currency: currencyName,
            usage: {
                total: totalTotalTokens,
                prompt: totalPromptTokens,
                candidates: totalCandidateTokens,
                durationMs: duration
            }
        };

    } catch (aiError: any) {
        console.error("AI Error -> Refund:", aiError);
        try {
            await db.runTransaction(async (t) => {
                const userRef = db.collection("users").doc(uid);
                const doc = await t.get(userRef);
                if (doc.exists) {
                    const currentBal = doc.data()?.[requiredCurrency] || 0;
                    t.update(userRef, { [requiredCurrency]: currentBal + cost });

                    const txRef = userRef.collection("transactions").doc();
                    t.set(txRef, {
                        uid,
                        type: "REFUND",
                        amount: cost,
                        balance_snapshot: currentBal + cost,
                        currency: currencyName,
                        description: `Error Refund: ${aiError.message}`,
                        timestamp: admin.firestore.FieldValue.serverTimestamp()
                    });
                }
            });
        } catch (e) { console.error("Refund Failed", e); }

        throw new HttpsError("internal", `번역 실패: ${aiError.message}`);
    }
});


export const translateNovelTxt = onCall({
    region: "asia-northeast3",
    secrets: ["GEMINI_API_KEY"],
    memory: "512MiB",
    timeoutSeconds: 300
}, async (request) => {
    const { data, auth } = request;

    if (!auth) {
        throw new HttpsError("unauthenticated", "로그인이 필요합니다.");
    }

    const text = String(data.text || "").trim();
    const targetLangRaw = String(data.targetLang || "KO").toUpperCase();
    const modelTier = String(data.modelTier || "ADVANCED").toUpperCase();

    if (!text) {
        throw new HttpsError("invalid-argument", "번역할 텍스트가 없습니다.");
    }

    if (text.length <= 100) {
        throw new HttpsError("invalid-argument", "소설 번역은 100자 초과 텍스트부터 가능합니다.");
    }

    if (text.length > 20000) {
        throw new HttpsError("invalid-argument", "한 번에 번역 가능한 최대 글자 수는 20,000자입니다.");
    }

    const targetLang = targetLangRaw === "EN" ? "English"
        : targetLangRaw === "JA" ? "Japanese"
            : targetLangRaw === "ZH" ? "Chinese"
                : "Korean";

    const resolvedTier = modelTier === "PRO" ? "PRO" : "ADVANCED";
    const ratePer100 = resolvedTier === "PRO" ? 20 : 2;
    const cost = Math.floor((text.length * ratePer100) / 100);
    const modelName = resolvedTier === "PRO" ? "gemini-3-pro-preview" : "gemini-3-flash-preview";

    const uid = auth.uid;
    const email = auth.token.email || "";
    const requiredCurrency = "current_balance";
    const currencyName = "Silver";

    await db.runTransaction(async (transaction) => {
        const userRef = db.collection("users").doc(uid);
        const userDoc = await transaction.get(userRef);

        if (!userDoc.exists) {
            throw new HttpsError("not-found", "지갑 정보가 없습니다.");
        }

        const balance = userDoc.data()?.[requiredCurrency] || 0;
        console.log(`[소설번역] 요청 uid=${uid}, tier=${resolvedTier}, chars=${text.length}, cost=${cost}, balance=${balance}`);
        if (balance < cost) {
            console.warn(`[소설번역] 잔액 부족 uid=${uid}, required=${cost}, balance=${balance}`);
            throw new HttpsError("resource-exhausted", `${currencyName}가 부족합니다.`);
        }

        const newBalance = balance - cost;
        transaction.update(userRef, { [requiredCurrency]: newBalance });

        const txRef = userRef.collection("transactions").doc();
        transaction.set(txRef, {
            uid,
            type: "USE",
            amount: -cost,
            balance_snapshot: newBalance,
            currency: currencyName,
            description: `Novel Translation (${text.length} chars, ${modelName})`,
            timestamp: admin.firestore.FieldValue.serverTimestamp()
        });
    });

    try {
        console.log(`[소설번역] AI 요청 시작 uid=${uid}, model=${modelName}`);
        const geminiApiKey = process.env.GEMINI_API_KEY;
        if (!geminiApiKey) {
            throw new Error("GEMINI API 키가 설정되지 않았습니다.");
        }

        const genAI = new GoogleGenerativeAI(geminiApiKey);
        const model = genAI.getGenerativeModel({ model: modelName });

        const prompt = `You are a novel translator.
No chitchat, no explanations, and no markdown.
Output only the final translated text immediately.
Translate naturally into ${targetLang}.`;

        const result = await model.generateContent({
            contents: [{ role: "user", parts: [{ text: `${prompt}

${text}` }] }],
            generationConfig: {
                temperature: 0.6,
                responseMimeType: "text/plain"
            }
        });

        const response = await result.response;
        const translatedText = response.text().trim();
        console.log(`[소설번역] AI 응답 완료 uid=${uid}, outChars=${translatedText.length}`);

        await db.collection("users").doc(uid).set({
            email,
            total_used_silver: admin.firestore.FieldValue.increment(cost),
            total_translated_pages: admin.firestore.FieldValue.increment(1),
            last_active_at: admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });

        return {
            success: true,
            translatedText,
            cost,
            currency: currencyName,
            charCount: text.length,
            format: "txt",
            model: modelName
        };

    } catch (error: any) {
        console.error(`[소설번역] 실패 uid=${uid}`, error);
        await db.runTransaction(async (transaction) => {
            const userRef = db.collection("users").doc(uid);
            const userDoc = await transaction.get(userRef);

            if (!userDoc.exists) return;
            const currentBalance = userDoc.data()?.[requiredCurrency] || 0;
            const rollbackBalance = currentBalance + cost;

            transaction.update(userRef, { [requiredCurrency]: rollbackBalance });

            const txRef = userRef.collection("transactions").doc();
            transaction.set(txRef, {
                uid,
                type: "REFUND",
                amount: cost,
                balance_snapshot: rollbackBalance,
                currency: currencyName,
                description: `Novel translation refund: ${error.message}`,
                timestamp: admin.firestore.FieldValue.serverTimestamp()
            });
        });

        throw new HttpsError("internal", error.message || "소설 번역 실패");
    }
});


/**
 * [기능 2] 인앱 결제 검증 및 재화(실버/골드) 지급 (v2)
 */
export const purchaseVnCredits = onCall({
    region: "asia-northeast3"
}, async (request) => {
    const {data, auth} = request;
    if (!auth) {
        throw new HttpsError("unauthenticated", "Login required.");
    }

    const uid = auth.uid;
    const packageId = String(data.packageId || "").toUpperCase() as VnPackageId;
    const pkg = VN_PACKAGES[packageId];
    if (!pkg) {
        throw new HttpsError("invalid-argument", "Invalid package id.");
    }

    const result = await db.runTransaction(async (transaction) => {
        const userRef = db.collection("users").doc(uid);
        const userDoc = await transaction.get(userRef);
        if (!userDoc.exists) {
            throw new HttpsError("not-found", "User not found.");
        }

        const silver = userDoc.get("current_balance") || 0;
        const vnChars = userDoc.get("vn_char_balance") || 0;

        if (silver < pkg.costSilver) {
            throw new HttpsError("resource-exhausted", "Insufficient Silver.");
        }
        if (vnChars + pkg.addChars > VN_CHAR_MAX) {
            throw new HttpsError("invalid-argument", "VN character cap exceeded.");
        }

        const newSilver = silver - pkg.costSilver;
        const newVnChars = vnChars + pkg.addChars;

        transaction.update(userRef, {
            current_balance: newSilver,
            vn_char_balance: newVnChars
        });

        const txRef = userRef.collection("transactions").doc();
        transaction.set(txRef, {
            uid,
            type: "USE_SILVER_FOR_VN_TOPUP",
            amount: -pkg.costSilver,
            balance_snapshot: newSilver,
            currency: "Silver",
            description: `VN top-up ${packageId} (+${pkg.addChars} chars)`,
            timestamp: admin.firestore.FieldValue.serverTimestamp()
        });

        return {
            newSilverBalance: newSilver,
            newVnCharBalance: newVnChars
        };
    });

    return {
        success: true,
        newSilverBalance: result.newSilverBalance,
        newVnCharBalance: result.newVnCharBalance
    };
});

export const translateVnFast = onCall({
    region: "asia-northeast3",
    secrets: ["DEEPL_API_KEY"],
    memory: "512MiB",
    timeoutSeconds: 120
}, async (request) => {
    const {data, auth} = request;
    if (!auth) {
        throw new HttpsError("unauthenticated", "Login required.");
    }

    const uid = auth.uid;
    const requests = data.requests;
    if (!Array.isArray(requests) || requests.length === 0) {
        throw new HttpsError("invalid-argument", "requests must be a non-empty array.");
    }

    const targetLang = normalizeDeeplCode(data.targetLang);
    const sourceLang = data.sourceLang == null ? undefined : normalizeDeeplCode(data.sourceLang);

    const cleanedRequests = requests.map((item: any, index: number) => {
        const id = item?.id ?? index;
        const text = String(item?.text ?? "");
        const cleanText = text.replace(/\r/g, "").trim();
        return {id, text: cleanText};
    });

    const totalInputChars = cleanedRequests.reduce((sum: number, item: any) => sum + item.text.length, 0);
    if (totalInputChars > VN_INPUT_CHAR_LIMIT) {
        throw new HttpsError("invalid-argument", "Input character limit exceeded.");
    }

    const deeplApiKey = process.env.DEEPL_API_KEY;
    if (!deeplApiKey) {
        throw new HttpsError("internal", "DEEPL_API_KEY is not configured.");
    }

    const deeplBaseUrl = (process.env.DEEPL_API_BASE_URL || "https://api.deepl.com").replace(/\/+$/, "");
    const translateUrl = `${deeplBaseUrl}/v2/translate`;

    const form = new URLSearchParams();
    cleanedRequests.forEach((item) => form.append("text", item.text));
    form.append("target_lang", targetLang);
    if (sourceLang) {
        form.append("source_lang", sourceLang);
    }

    let translatedTexts: string[];
    try {
        const deeplResponse = await fetch(translateUrl, {
            method: "POST",
            headers: {
                "Authorization": `DeepL-Auth-Key ${deeplApiKey}`,
                "Content-Type": "application/x-www-form-urlencoded"
            },
            body: form.toString()
        });

        if (!deeplResponse.ok) {
            const bodyText = await deeplResponse.text();
            console.error("DeepL API error:", deeplResponse.status, bodyText);
            throw new Error("DeepL translation failed.");
        }

        const deeplJson = await deeplResponse.json() as {translations?: Array<{text?: string}>};
        const translations = deeplJson.translations || [];
        if (translations.length !== cleanedRequests.length) {
            throw new Error("DeepL response count mismatch.");
        }

        translatedTexts = translations.map((t) => String(t.text || "").replace(/\n/g, ""));
    } catch (e: any) {
        console.error("translateVnFast DeepL failure:", e);
        throw new HttpsError("internal", "DeepL translation failed.");
    }

    const usedOutputChars = translatedTexts.reduce((sum, text) => sum + text.length, 0);

    const remainingVnChars = await db.runTransaction(async (transaction) => {
        const userRef = db.collection("users").doc(uid);
        const userDoc = await transaction.get(userRef);
        if (!userDoc.exists) {
            throw new HttpsError("not-found", "User not found.");
        }

        const vnBalance = userDoc.get("vn_char_balance") || 0;
        if (vnBalance < usedOutputChars) {
            throw new HttpsError("resource-exhausted", "Insufficient VN chars.");
        }

        const newVnBalance = vnBalance - usedOutputChars;
        transaction.update(userRef, {
            vn_char_balance: newVnBalance
        });

        const txRef = userRef.collection("transactions").doc();
        transaction.set(txRef, {
            uid,
            type: "USE_VN_CHARS",
            amount: -usedOutputChars,
            balance_snapshot: newVnBalance,
            currency: "VN_CHARS",
            description: "VN fast translation",
            timestamp: admin.firestore.FieldValue.serverTimestamp()
        });

        return newVnBalance;
    });

    return {
        success: true,
        results: cleanedRequests.map((item, index) => ({
            id: item.id,
            text: translatedTexts[index] || ""
        })),
        usedOutputChars,
        remainingVnChars
    };
});

export const verifyPurchase = onCall({
    region: "asia-northeast3"
}, async (request) => {

    console.log("[Server] verifyPurchase called");

    const { data, auth } = request;
    if (!auth) throw new HttpsError("unauthenticated", "로그인이 필요합니다.");

    const uid = auth.uid;
    const productId = data.productId;
    const purchaseToken = data.purchaseToken;

    if (!productId || !purchaseToken) {
        throw new HttpsError("invalid-argument", "결제 정보가 부족합니다.");
    }

    let amount = 0;
    let currencyField = "current_balance";
    let currencyName = "Silver";

    if (productId === "silver_50") amount = 50;
    else if (productId === "silver_100") amount = 100;
    else if (productId === "silver_500") amount = 500;
    else if (productId === "gold_50") { amount = 50; currencyField = "gold_balance"; currencyName = "Gold"; }
    else if (productId === "gold_100") { amount = 100; currencyField = "gold_balance"; currencyName = "Gold"; }
    else if (productId === "gold_500") { amount = 500; currencyField = "gold_balance"; currencyName = "Gold"; }
    else {
        throw new HttpsError("not-found", "알 수 없는 상품입니다.");
    }

    const tokenCheck = await db.collection("purchase_logs").doc(purchaseToken).get();
    if (tokenCheck.exists) {
        console.warn("⚠️ [Server] 이미 처리된 결제 요청입니다.");
        throw new HttpsError("already-exists", "이미 지급된 결제입니다.");
    }

    console.log(`✅ [Server] 결제 승인: ${productId} (+${amount} ${currencyName})`);

    try {
        await db.runTransaction(async (transaction) => {
            const userRef = db.collection("users").doc(uid);
            const userDoc = await transaction.get(userRef);

            if (!userDoc.exists) {
                transaction.set(userRef, { current_balance: 0, gold_balance: 0 });
            }

            const current = userDoc.exists ? (userDoc.data()?.[currencyField] || 0) : 0;
            const newBalance = current + amount;

            transaction.update(userRef, { [currencyField]: newBalance });

            const txRef = userRef.collection("transactions").doc();
            transaction.set(txRef, {
                uid,
                type: "CHARGE",
                amount: amount,
                balance_snapshot: newBalance,
                currency: currencyName,
                description: `In-App Purchase (${productId})`,
                productId: productId,
                purchaseToken: purchaseToken,
                timestamp: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.set(db.collection("purchase_logs").doc(purchaseToken), {
                uid,
                productId,
                timestamp: admin.firestore.FieldValue.serverTimestamp()
            });
        });

        return {
            success: true,
            message: "충전 완료",
            addedAmount: amount,
            currency: currencyName
        };

    } catch (e: any) {
        console.error("❌ [Server] 충전 트랜잭션 실패:", e);
        throw new HttpsError("internal", "충전 처리 중 오류가 발생했습니다.");
    }
});


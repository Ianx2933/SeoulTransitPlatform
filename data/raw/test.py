import pandas as pd

df = pd.read_csv("data/raw/노선별OD_20251111.csv", encoding="cp949")
print(df.columns.tolist())
print(df.head(2))